package com.ishitv.urlshortener.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.ishitv.urlshortener.config.AppProperties;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Cache-aside storage for short codes. Every Redis key the cache uses is defined in this class:
 *
 * <pre>
 *   code:{X}          HASH   url, created_at, clicks   TTL 1h ± 10%   forward lookup + stats
 *   miss:{X}          STRING "1"                       TTL 60s        negative cache / delete tombstone
 *   rev:&lt;sha256(url)&gt; STRING X                         TTL 24h        shorten de-duplication
 * </pre>
 *
 * The braces are Redis Cluster <em>hash tags</em>: only the text inside {...} is hashed to pick a slot,
 * so {@code code:{X}} and {@code miss:{X}} always live on the same shard. That lets the seed script read
 * both atomically; a multi-key script across shards would fail with CROSSSLOT.
 *
 * <p><b>Redis is an optimisation, not a dependency.</b> Every method catches Redis failures, counts them,
 * and returns "not cached", so callers fall back to Postgres. The Python service returned 500 instead.
 */
@Component
public class UrlCache {

    private static final Logger log = LoggerFactory.getLogger(UrlCache.class);

    /**
     * Writes the hash only if nothing is there yet and the code isn't tombstoned. Atomic, so:
     * <ul>
     *   <li>two concurrent cache misses can't overwrite each other's click count (first writer wins);</li>
     *   <li>a redirect that read a lagging replica can't resurrect a code deleted a moment ago.</li>
     * </ul>
     * HSET + EXPIRE in one script also means a hash can never exist without a TTL.
     */
    private static final RedisScript<Long> SEED = RedisScript.of("""
            if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('HSET', KEYS[1], 'url', ARGV[1], 'created_at', ARGV[2], 'clicks', ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final AppProperties.Cache settings;
    private final MeterRegistry metrics;

    public UrlCache(StringRedisTemplate redis, AppProperties properties, MeterRegistry metrics) {
        this.redis = redis;
        this.settings = properties.cache();
        this.metrics = metrics;
    }

    // ---- forward lookup: code:{X} ---------------------------------------------------------------

    /** Just the URL, for redirects (one HGET). */
    public Optional<String> findUrl(String code) {
        Optional<String> url = safely("findUrl", Optional::empty,
                () -> Optional.ofNullable((String) redis.opsForHash().get(codeKey(code), "url")));
        recordLookup(url.isPresent());
        return url;
    }

    /** The whole hash, for stats (one HGETALL). */
    public Optional<CachedUrl> find(String code) {
        Optional<CachedUrl> cached = safely("find", Optional::empty, () -> {
            Map<Object, Object> hash = redis.opsForHash().entries(codeKey(code));
            if (hash.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new CachedUrl(
                    (String) hash.get("url"),
                    LocalDateTime.parse((String) hash.get("created_at")),
                    Long.parseLong((String) hash.get("clicks"))));
        });
        recordLookup(cached.isPresent());
        return cached;
    }

    /** Cache-aside fill after a DB read. No-op if already cached or tombstoned (see {@link #SEED}). */
    public void put(String code, String url, LocalDateTime createdAt, long clicks) {
        safely("put", () -> null, () -> redis.execute(SEED, List.of(codeKey(code), missKey(code)),
                url, createdAt.toString(), Long.toString(clicks), Long.toString(jitteredTtl().toSeconds())));
    }

    /** For a code that was just inserted: clear any stale "doesn't exist" marker, then fill. */
    public void putNew(String code, String url, LocalDateTime createdAt) {
        safely("putNew", () -> null, () -> redis.delete(missKey(code)));
        put(code, url, createdAt, 0);
    }

    // ---- negative cache: miss:{X} ---------------------------------------------------------------

    public boolean isKnownMissing(String code) {
        boolean missing = safely("isKnownMissing", () -> false, () -> Boolean.TRUE.equals(redis.hasKey(missKey(code))));
        if (missing) {
            metrics.counter("urlshortener.cache.lookups", "result", "negative_hit").increment();
        }
        return missing;
    }

    public void markMissing(String code) {
        safely("markMissing", () -> null, () -> {
            redis.opsForValue().set(missKey(code), "1", settings.negativeTtl());
            return null;
        });
    }

    // ---- reverse lookup: rev:<sha256(url)> --------------------------------------------------------

    public Optional<String> findCodeForUrl(String url) {
        return safely("findCodeForUrl", Optional::empty,
                () -> Optional.ofNullable(redis.opsForValue().get(reverseKey(url))));
    }

    public void rememberCodeForUrl(String url, String code) {
        safely("rememberCodeForUrl", () -> null, () -> {
            redis.opsForValue().set(reverseKey(url), code, settings.reverseTtl());
            return null;
        });
    }

    // ---- invalidation ---------------------------------------------------------------------------

    /**
     * Called <em>after</em> the DB delete has committed. Removes the cached entry and the reverse mapping,
     * and leaves a tombstone so that, for the next {@code negative-ttl}, no cache-miss path can re-seed
     * the entry from a replica that hasn't applied the delete yet.
     */
    public void evict(String code, String url) {
        safely("evict", () -> null, () -> {
            redis.delete(List.of(codeKey(code), reverseKey(url)));
            redis.opsForValue().set(missKey(code), "1", settings.negativeTtl());
            return null;
        });
    }

    // ---- keys -----------------------------------------------------------------------------------

    public static String codeKey(String code) {
        return "code:{" + code + "}";
    }

    public static String missKey(String code) {
        return "miss:{" + code + "}";
    }

    /** URLs can be 1500 bytes; hashing bounds the key size (and keeps arbitrary input out of key names). */
    public static String reverseKey(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            return "rev:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * TTL ± jitter. Without it, entries created in the same burst expire in the same second, and their
     * simultaneous misses hit the database together (a cache stampede).
     */
    Duration jitteredTtl() {
        double factor = 1 + ThreadLocalRandom.current().nextDouble(-settings.ttlJitter(), settings.ttlJitter());
        return Duration.ofSeconds(Math.max(1, Math.round(settings.urlTtl().toSeconds() * factor)));
    }

    private void recordLookup(boolean hit) {
        metrics.counter("urlshortener.cache.lookups", "result", hit ? "hit" : "miss").increment();
    }

    private <T> T safely(String operation, Supplier<T> fallback, Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            metrics.counter("urlshortener.cache.errors", "operation", operation).increment();
            log.warn("Redis {} failed, falling back to the database: {}", operation, e.getMessage());
            return fallback.get();
        }
    }
}
