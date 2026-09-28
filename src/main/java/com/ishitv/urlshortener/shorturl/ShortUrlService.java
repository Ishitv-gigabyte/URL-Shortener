package com.ishitv.urlshortener.shorturl;

import java.time.Clock;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.ishitv.urlshortener.cache.CachedUrl;
import com.ishitv.urlshortener.cache.UrlCache;
import com.ishitv.urlshortener.clicks.ClickCounter;
import com.ishitv.urlshortener.error.CodeGenerationException;
import com.ishitv.urlshortener.error.ShortCodeNotFoundException;
import com.ishitv.urlshortener.error.UrlTooLongException;

/**
 * Business logic for the four URL operations, using Redis as a cache-aside layer in front of Postgres.
 *
 * <p>Click counts: Postgres holds everything flushed so far, {@link ClickCounter} holds deltas not yet
 * flushed, and the cached {@code code:{X}} hash holds a display total (seeded as DB + pending, then
 * incremented on every redirect). The display total is eventually consistent; the DB is exact.
 *
 * <p>Not {@code @Transactional}: each repository call is its own short transaction, and its read-only
 * flag picks primary vs replica (see {@link ShortCodeRepository}). That also means no database connection
 * is held while this class talks to Redis.
 */
@Service
public class ShortUrlService {

    static final int MAX_URL_LENGTH = 1500;
    static final int MAX_ATTEMPTS = 10;

    private final ShortCodeRepository repository;
    private final ShortCodeGenerator generator;
    private final UrlCache cache;
    private final ClickCounter clicks;
    private final Clock clock;

    public ShortUrlService(ShortCodeRepository repository, ShortCodeGenerator generator, UrlCache cache,
                           ClickCounter clicks, Clock clock) {
        this.repository = repository;
        this.generator = generator;
        this.cache = cache;
        this.clicks = clicks;
        this.clock = clock;
    }

    public ShortenedUrl shorten(String submittedUrl) {
        String url = normalize(submittedUrl);

        Optional<ShortenedUrl> existing = findRecentlyShortened(url);
        if (existing.isPresent()) {
            return existing.get();
        }

        ShortenedUrl created = insertWithUniqueCode(url);
        cache.putNew(created.shortCode(), url, created.createdAt());
        cache.rememberCodeForUrl(url, created.shortCode());
        return created;
    }

    /** Returns the original URL to redirect to, and counts the click. */
    public String resolve(String shortCode) {
        requireWellFormed(shortCode);

        Optional<String> cached = cache.findUrlAndCountClick(shortCode);
        String url;
        if (cached.isPresent()) {
            url = cached.get();
        } else {
            url = loadFromReplica(shortCode).getOriginalUrl(); // re-seeds code:{X}
            cache.countClick(shortCode);
        }
        clicks.recordClick(shortCode);
        return url;
    }

    public UrlStats stats(String shortCode) {
        requireWellFormed(shortCode);

        Optional<CachedUrl> cached = cache.find(shortCode);
        if (cached.isPresent()) {
            CachedUrl hit = cached.get();
            return new UrlStats(hit.clicks(), hit.originalUrl(), hit.createdAt());
        }
        ShortCode row = loadFromReplica(shortCode);
        // B1: the Python service returned 0 here whenever the Redis counter was missing.
        return new UrlStats(totalClicks(row), row.getOriginalUrl(), row.getCreatedAt());
    }

    public void delete(String shortCode) {
        requireWellFormed(shortCode);

        // Primary, not replica: deleting something the replica hasn't seen yet must still work, and we need
        // the URL to remove its reverse-lookup key.
        ShortCode row = repository.findByShortCodeOnPrimary(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
        if (repository.deleteByShortCode(shortCode) == 0) {
            throw new ShortCodeNotFoundException(shortCode); // a concurrent DELETE won
        }
        // Invalidate only after the DELETE has committed. Invalidating first (as the Python service did)
        // leaves a window where a concurrent cache miss re-reads the still-present row and re-caches it.
        cache.evict(shortCode, row.getOriginalUrl());
        clicks.forget(shortCode);
    }

    /**
     * The reverse-lookup cache lets a repeated URL return its existing code. It is a best-effort de-dup
     * (24h TTL), not a uniqueness guarantee: there is no unique index on original_url.
     */
    private Optional<ShortenedUrl> findRecentlyShortened(String url) {
        Optional<String> code = cache.findCodeForUrl(url);
        if (code.isEmpty()) {
            return Optional.empty();
        }
        Optional<CachedUrl> cached = cache.find(code.get());
        if (cached.isPresent()) {
            return Optional.of(new ShortenedUrl(code.get(), url, cached.get().createdAt()));
        }
        // The code:{X} entry expired or was evicted while rev: survived. Confirm on the primary, since the
        // replica may not have the row yet. (The Python service crashed here once; see README history.)
        return repository.findByShortCodeOnPrimary(code.get()).map(row -> {
            cache.put(row.getShortCode(), row.getOriginalUrl(), row.getCreatedAt(), totalClicks(row));
            return new ShortenedUrl(row.getShortCode(), url, row.getCreatedAt());
        });
    }

    private ShortenedUrl insertWithUniqueCode(String url) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ShortCode candidate = new ShortCode(generator.next(), url, Timestamps.nowUtc(clock));
            try {
                // save() runs in its own transaction; a duplicate code rolls back only this attempt.
                ShortCode saved = repository.save(candidate);
                return new ShortenedUrl(saved.getShortCode(), saved.getOriginalUrl(), saved.getCreatedAt());
            } catch (DataIntegrityViolationException duplicateCode) {
                // 62^10 keyspace: effectively unreachable, but the unique constraint is the real guarantee.
            }
        }
        throw new CodeGenerationException(MAX_ATTEMPTS);
    }

    /** Cache miss path shared by redirect and stats: negative cache → replica → fill the cache. */
    private ShortCode loadFromReplica(String shortCode) {
        if (cache.isKnownMissing(shortCode)) {
            throw new ShortCodeNotFoundException(shortCode);
        }
        Optional<ShortCode> row = repository.findByShortCode(shortCode);
        if (row.isEmpty()) {
            cache.markMissing(shortCode);
            throw new ShortCodeNotFoundException(shortCode);
        }
        ShortCode found = row.get();
        cache.put(shortCode, found.getOriginalUrl(), found.getCreatedAt(), totalClicks(found));
        return found;
    }

    /** Flushed clicks (DB) + clicks still waiting in Redis. */
    private long totalClicks(ShortCode row) {
        return row.getClicks() + clicks.pendingClicks(row.getShortCode());
    }

    /** Same rule as the Python service: add https:// when no http(s) scheme is present. */
    static String normalize(String submittedUrl) {
        String url = submittedUrl.startsWith("http://") || submittedUrl.startsWith("https://")
                ? submittedUrl
                : "https://" + submittedUrl;
        if (url.length() > MAX_URL_LENGTH) {
            throw new UrlTooLongException(submittedUrl);
        }
        return url;
    }

    private static void requireWellFormed(String shortCode) {
        if (!ShortCodeGenerator.isWellFormed(shortCode)) {
            throw new ShortCodeNotFoundException(shortCode);
        }
    }
}
