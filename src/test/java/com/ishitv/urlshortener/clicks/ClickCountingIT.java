package com.ishitv.urlshortener.clicks;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ishitv.urlshortener.cache.UrlCache;
import com.ishitv.urlshortener.support.Http;
import com.ishitv.urlshortener.support.IntegrationTest;

/**
 * Click counting end to end: redirect → Redis delta → flush → Postgres. Ports the click tests from
 * tests/test_api_endpoints.py and pins the fixes for B1, B2 and B3.
 */
class ClickCountingIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ClickFlushJob flushJob;

    @Autowired
    @Qualifier("primaryDataSource")
    private DataSource primary;

    private Http http;
    private JdbcTemplate db;

    @BeforeEach
    void setUp() {
        http = new Http(port);
        db = new JdbcTemplate(primary);
    }

    @Test
    void redirectIncrementsClicksImmediatelyInStats() {
        String code = shorten("https://example.com/one-click");
        assertThat(clicksInStats(code)).isZero();

        http.get("/" + code);

        assertThat(clicksInStats(code)).isEqualTo(1);
    }

    @Test
    void hundredRapidRedirectsAreAllCounted() {
        String code = shorten("https://example.com/rapid");

        for (int i = 0; i < 100; i++) {
            assertThat(http.get("/" + code).status()).isEqualTo(302);
        }

        assertThat(clicksInStats(code)).isEqualTo(100);
    }

    @Test
    void flushMovesPendingDeltasIntoPostgres() {
        String code = shorten("https://example.com/flush");
        for (int i = 0; i < 5; i++) {
            http.get("/" + code);
        }
        assertThat(clicksInDb(code)).isZero();

        flushJob.flushOnce();

        assertThat(clicksInDb(code)).isEqualTo(5);
        assertThat(redis.opsForHash().get(ClickCounter.PENDING_KEY, code)).isNull();
        assertThat(clicksInStats(code)).isEqualTo(5);
    }

    @Test
    void statsFallsBackToDbPlusPendingWhenCacheEntryIsGone() {
        // B1: the Python service reported 0 clicks whenever the Redis counter was missing.
        String code = shorten("https://example.com/b1");
        for (int i = 0; i < 3; i++) {
            http.get("/" + code);
        }
        flushJob.flushOnce();              // DB = 3
        http.get("/" + code);              // pending = 1
        redis.delete(UrlCache.codeKey(code));

        assertThat(clicksInStats(code)).isEqualTo(4);
    }

    @Test
    void evictedCounterNeverResetsTheDatabaseTotal() {
        // B2: an evicted absolute counter used to restart at 1 and then overwrite the DB total.
        String code = shorten("https://example.com/b2");
        for (int i = 0; i < 10; i++) {
            http.get("/" + code);
        }
        flushJob.flushOnce();                           // DB = 10
        redis.delete(UrlCache.codeKey(code));           // simulate eviction of the cache entry

        http.get("/" + code);                           // re-seeds from DB (10) + counts 1
        flushJob.flushOnce();

        assertThat(clicksInDb(code)).isEqualTo(11);
        assertThat(clicksInStats(code)).isEqualTo(11);
        assertThat(redis.getExpire(UrlCache.codeKey(code))).as("re-seeded entry has a TTL").isPositive();
    }

    @Test
    void deleteDiscardsPendingClicks() {
        // B3: a click racing a delete used to leave an immortal clicks:{code} key behind.
        String code = shorten("https://example.com/b3");
        http.get("/" + code);

        http.delete("/" + code);

        assertThat(redis.opsForHash().get(ClickCounter.PENDING_KEY, code)).isNull();
    }

    @Test
    void reprocessingAnAlreadyAppliedBatchDoesNotDoubleCount() {
        // Simulates a crash after the DB commit but before the batch was deleted from Redis.
        String code = shorten("https://example.com/idempotent");
        String staleId = (System.currentTimeMillis() - Duration.ofMinutes(10).toMillis()) + "-crashed-run";
        redis.opsForHash().put(ClickFlushJob.FLUSHING_PREFIX + staleId, code, "7");
        db.update("insert into click_flush_batches (batch_id) values (?)", staleId);

        flushJob.flushOnce();

        assertThat(clicksInDb(code)).isZero();
        assertThat(redis.hasKey(ClickFlushJob.FLUSHING_PREFIX + staleId)).isFalse();
    }

    @Test
    void orphanedBatchFromACrashedRunIsApplied() {
        // Simulates a crash after RENAME but before the DB transaction.
        String code = shorten("https://example.com/orphan");
        String staleId = (System.currentTimeMillis() - Duration.ofMinutes(10).toMillis()) + "-crashed-run2";
        redis.opsForHash().put(ClickFlushJob.FLUSHING_PREFIX + staleId, code, "4");

        flushJob.flushOnce();

        assertThat(clicksInDb(code)).isEqualTo(4);
        assertThat(db.queryForObject("select count(*) from click_flush_batches where batch_id = ?",
                Integer.class, staleId)).isEqualTo(1);
    }

    @Test
    void recentInFlightBatchIsLeftForItsOwner() {
        String code = shorten("https://example.com/in-flight");
        String freshId = System.currentTimeMillis() + "-other-instance";
        redis.opsForHash().put(ClickFlushJob.FLUSHING_PREFIX + freshId, code, "2");

        flushJob.flushOnce();

        assertThat(clicksInDb(code)).isZero();
        redis.delete(ClickFlushJob.FLUSHING_PREFIX + freshId);
    }

    @Test
    void pendingHashHasNoTtlSoVolatileEvictionCannotDropClicks() {
        String code = shorten("https://example.com/no-ttl");
        http.get("/" + code);

        assertThat(redis.getExpire(ClickCounter.PENDING_KEY)).isEqualTo(-1L);
    }

    private String shorten(String url) {
        return http.postJson("/shorten", "{\"original_url\":\"" + url + "\"}").shortCode();
    }

    private long clicksInStats(String code) {
        String body = http.get("/stats/" + code).body();
        return Long.parseLong(body.replaceAll(".*\"clicks\":(\\d+).*", "$1"));
    }

    private int clicksInDb(String code) {
        return db.queryForObject("select clicks from codes where short_code_chars = ?", Integer.class, code);
    }
}
