package com.ishitv.urlshortener.shorturl.api;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ishitv.urlshortener.cache.UrlCache;
import com.ishitv.urlshortener.support.Http;
import com.ishitv.urlshortener.support.IntegrationTest;
import com.ishitv.urlshortener.support.SeparateReplica;

/**
 * Which database each endpoint uses, observed through the HTTP API. Ports TestAppWritesToPrimary and
 * TestAppReadsFromReplica from tests/test_read_replica.py. The cache entry is removed where needed so
 * the request actually reaches a database.
 */
class ApiRoutingIT extends IntegrationTest {

    @DynamicPropertySource
    static void separateReplica(DynamicPropertyRegistry registry) {
        SeparateReplica.register(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("primaryDataSource")
    private DataSource primaryDataSource;

    @Autowired
    private StringRedisTemplate redis;

    private Http http;
    private JdbcTemplate primary;

    @BeforeEach
    void setUp() {
        http = new Http(port);
        primary = new JdbcTemplate(primaryDataSource);
    }

    @Test
    void shortenWritesToPrimaryOnly() {
        String code = shorten("https://example.com/routing-write");

        assertThat(count(primary, code)).isEqualTo(1);
        assertThat(count(SeparateReplica.replicaWriter(), code)).isZero();
    }

    @Test
    void redirectCacheMissIsServedByReplica() {
        String code = shorten("https://example.com/routing-redirect");
        SeparateReplica.replicate(primary, code);
        redis.delete(UrlCache.codeKey(code));

        Http.Response response = http.get("/" + code);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("Location")).hasValue("https://example.com/routing-redirect");
    }

    @Test
    void redirectCacheMiss404sUntilReplicated() {
        String code = shorten("https://example.com/routing-lag");
        redis.delete(UrlCache.codeKey(code)); // only the primary has the row now

        assertThat(http.get("/" + code).status()).isEqualTo(404);
    }

    @Test
    void statsCacheMissIsServedByReplica() {
        String code = shorten("https://example.com/routing-stats");
        SeparateReplica.replicate(primary, code);
        redis.delete(UrlCache.codeKey(code));

        assertThat(http.get("/stats/" + code).status()).isEqualTo(200);
    }

    @Test
    void deleteUsesPrimaryEvenBeforeReplication() {
        String code = shorten("https://example.com/routing-delete");

        assertThat(http.delete("/" + code).status()).isEqualTo(204);
        assertThat(count(primary, code)).isZero();
    }

    private String shorten(String url) {
        return http.postJson("/shorten", "{\"original_url\":\"" + url + "\"}").shortCode();
    }

    private static int count(JdbcTemplate jdbc, String code) {
        return jdbc.queryForObject("select count(*) from codes where short_code_chars = ?", Integer.class, code);
    }
}
