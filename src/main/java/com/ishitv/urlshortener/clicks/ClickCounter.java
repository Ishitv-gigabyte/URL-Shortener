package com.ishitv.urlshortener.clicks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Click deltas that have not reached Postgres yet, in one hash: {@code {clicks}:pending} → code → n.
 *
 * <p><b>Postgres is the source of truth</b>; Redis only holds increments since the last flush. The Python
 * service stored absolute counts in Redis and copied them over the DB, so a reset Redis counter
 * overwrote the real total (B2). Deltas can only ever <em>add</em> to the DB value.
 *
 * <p>The key has no TTL on purpose. With {@code maxmemory-policy volatile-lru}, Redis only evicts keys that
 * have a TTL, so un-flushed clicks are never evicted, only cache entries.
 */
@Component
public class ClickCounter {

    /** The {clicks} hash tag keeps pending and every flushing batch on one Cluster slot (RENAME needs that). */
    public static final String PENDING_KEY = "{clicks}:pending";

    private static final Logger log = LoggerFactory.getLogger(ClickCounter.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;

    public ClickCounter(StringRedisTemplate redis, MeterRegistry metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    /** One redirect. If Redis is unavailable the click is dropped and counted, never the redirect. */
    public void recordClick(String code) {
        try {
            redis.opsForHash().increment(PENDING_KEY, code, 1);
        } catch (DataAccessException e) {
            metrics.counter("urlshortener.clicks.dropped").increment();
            log.warn("Dropping click for {}: Redis unavailable ({})", code, e.getMessage());
        }
    }

    /** Clicks recorded but not yet flushed, to add to the DB value when (re)building a cache entry. */
    public long pendingClicks(String code) {
        try {
            Object pending = redis.opsForHash().get(PENDING_KEY, code);
            return pending == null ? 0 : Long.parseLong((String) pending);
        } catch (DataAccessException e) {
            return 0;
        }
    }

    /** After a delete: nothing left to flush for this code. */
    public void forget(String code) {
        try {
            redis.opsForHash().delete(PENDING_KEY, code);
        } catch (DataAccessException e) {
            // Harmless: the flush UPDATE matches no row for a deleted code.
            log.warn("Could not clear pending clicks for {}: {}", code, e.getMessage());
        }
    }
}
