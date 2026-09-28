package com.ishitv.urlshortener.shorturl;

import java.time.Clock;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.ishitv.urlshortener.error.CodeGenerationException;
import com.ishitv.urlshortener.error.ShortCodeNotFoundException;
import com.ishitv.urlshortener.error.UrlTooLongException;

/**
 * Business logic for the four URL operations.
 *
 * <p>Not {@code @Transactional}: each repository call is its own short transaction, and its read-only
 * flag picks primary vs replica (see {@link ShortCodeRepository}).
 */
@Service
public class ShortUrlService {

    static final int MAX_URL_LENGTH = 1500;
    static final int MAX_ATTEMPTS = 10;

    private final ShortCodeRepository repository;
    private final ShortCodeGenerator generator;
    private final Clock clock;

    public ShortUrlService(ShortCodeRepository repository, ShortCodeGenerator generator, Clock clock) {
        this.repository = repository;
        this.generator = generator;
        this.clock = clock;
    }

    public ShortenedUrl shorten(String submittedUrl) {
        String url = normalize(submittedUrl);

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

    /** Returns the original URL to redirect to. */
    public String resolve(String shortCode) {
        return findOnReplica(shortCode).getOriginalUrl();
    }

    public UrlStats stats(String shortCode) {
        ShortCode row = findOnReplica(shortCode);
        return new UrlStats(row.getClicks(), row.getOriginalUrl(), row.getCreatedAt());
    }

    public void delete(String shortCode) {
        requireWellFormed(shortCode);
        if (repository.deleteByShortCode(shortCode) == 0) {
            throw new ShortCodeNotFoundException(shortCode);
        }
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

    private ShortCode findOnReplica(String shortCode) {
        requireWellFormed(shortCode);
        return repository.findByShortCode(shortCode).orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
    }

    private static void requireWellFormed(String shortCode) {
        if (!ShortCodeGenerator.isWellFormed(shortCode)) {
            throw new ShortCodeNotFoundException(shortCode);
        }
    }
}
