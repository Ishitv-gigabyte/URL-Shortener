package com.ishitv.urlshortener.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ishitv.urlshortener.support.Http;
import com.ishitv.urlshortener.support.IntegrationTest;

/**
 * Cache behaviour against real Redis. Ports TestShortenCaching from tests/test_api_endpoints.py and adds
 * the new design's guarantees (single hash per code, tombstones, negative cache, TTL jitter).
 */
class CacheAsideIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private UrlCache cache;

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
    void sameUrlTwiceReturnsSameCodeAndOneRow() {
        String url = "https://example.com/cache-dedup";
        Http.Response first = http.postJson("/shorten", "{\"original_url\":\"" + url + "\"}");
        Http.Response second = http.postJson("/shorten", "{\"original_url\":\"" + url + "\"}");

        assertThat(second.body()).isEqualTo(first.body());
        assertThat(db.queryForObject("select count(*) from codes where original_url = ?", Integer.class, url)).isEqualTo(1);
    }

    @Test
    void shortenFillsOneHashWithUrlCreatedAtAndZeroClicks() {
        String code = shorten("https://example.com/forward-cache");

        Map<Object, Object> hash = redis.opsForHash().entries(UrlCache.codeKey(code));

        assertThat(hash).containsEntry("url", "https://example.com/forward-cache")
                .containsEntry("clicks", "0")
                .containsKey("created_at");
    }

    @Test
    void entryTtlIsOneHourWithJitterAndReverseTtlIsOneDay() {
        String url = "https://example.com/ttl-check";
        String code = shorten(url);

        assertThat(redis.getExpire(UrlCache.codeKey(code))).isBetween(3240L, 3960L);
        assertThat(redis.getExpire(UrlCache.reverseKey(url))).isBetween(86_000L, 86_400L);
    }

    @Test
    void redirectIsServedFromCacheWithoutTheDatabase() {
        String code = shorten("https://example.com/cache-only");
        db.update("delete from codes where short_code_chars = ?", code); // DB no longer has it

        Http.Response response = http.get("/" + code);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("Location")).hasValue("https://example.com/cache-only");
    }

    @Test
    void cacheMissIsFilledFromTheDatabase() {
        String code = shorten("https://example.com/refill");
        redis.delete(UrlCache.codeKey(code));

        assertThat(http.get("/" + code).status()).isEqualTo(302);
        assertThat(redis.opsForHash().get(UrlCache.codeKey(code), "url")).isEqualTo("https://example.com/refill");
    }

    @Test
    void statsIsServedFromCacheWithoutTheDatabase() {
        String code = shorten("https://example.com/stats-cache");
        db.update("delete from codes where short_code_chars = ?", code);

        Http.Response stats = http.get("/stats/" + code);

        assertThat(stats.status()).isEqualTo(200);
        assertThat(stats.body()).contains("\"original_url\":\"https://example.com/stats-cache\"");
    }

    @Test
    void shortenRecoversWhenOnlyTheReverseKeySurvived() {
        // The README's 2000-user crash: created_at was evicted while the reverse mapping survived.
        String url = "https://example.com/partial-eviction";
        String code = shorten(url);
        redis.delete(UrlCache.codeKey(code));

        Http.Response again = http.postJson("/shorten", "{\"original_url\":\"" + url + "\"}");

        assertThat(again.status()).isEqualTo(201);
        assertThat(again.shortCode()).isEqualTo(code);
        assertThat(redis.hasKey(UrlCache.codeKey(code))).isTrue();
    }

    @Test
    void deleteClearsEntryAndReverseKeyAndLeavesTombstone() {
        String url = "https://example.com/delete-reverse";
        String code = shorten(url);

        assertThat(http.delete("/" + code).status()).isEqualTo(204);

        assertThat(redis.hasKey(UrlCache.codeKey(code))).isFalse();
        assertThat(redis.hasKey(UrlCache.reverseKey(url))).isFalse();
        assertThat(redis.getExpire(UrlCache.missKey(code))).isBetween(1L, 60L);
    }

    @Test
    void deleteClearsReverseKeyEvenIfForwardEntryAlreadyExpired() {
        String url = "https://example.com/delete-expired-forward";
        String code = shorten(url);
        redis.delete(UrlCache.codeKey(code));

        http.delete("/" + code);

        assertThat(redis.hasKey(UrlCache.reverseKey(url))).isFalse();
    }

    @Test
    void tombstoneStopsAStaleReplicaReadFromReCachingADeletedCode() {
        String code = shorten("https://example.com/tombstone");
        http.delete("/" + code);

        // A redirect that read the replica *before* it applied the delete now tries to fill the cache.
        cache.put(code, "https://example.com/tombstone", LocalDateTime.now(), 0);

        assertThat(redis.hasKey(UrlCache.codeKey(code))).isFalse();
    }

    @Test
    void seedNeverOverwritesAnExistingEntry() {
        String code = shorten("https://example.com/first-writer-wins");
        redis.opsForHash().put(UrlCache.codeKey(code), "clicks", "7");

        cache.put(code, "https://example.com/first-writer-wins", LocalDateTime.now(), 0);

        assertThat(redis.opsForHash().get(UrlCache.codeKey(code), "clicks")).isEqualTo("7");
    }

    @Test
    void unknownCodeIsRememberedAsMissing() {
        assertThat(http.get("/NoSuchCode").status()).isEqualTo(404);

        assertThat(redis.getExpire(UrlCache.missKey("NoSuchCode"))).isBetween(1L, 60L);

        // Trade-off of negative caching: a row that appears in the DB (e.g. replication catching up) stays
        // invisible until the marker expires. Shorten avoids this by clearing the marker for new codes.
        db.update("insert into codes (clicks, short_code_chars, original_url, created_at) values (0, 'NoSuchCode', 'https://late.example', now())");
        assertThat(http.get("/NoSuchCode").status()).isEqualTo(404);
    }

    private String shorten(String url) {
        return http.postJson("/shorten", "{\"original_url\":\"" + url + "\"}").shortCode();
    }
}
