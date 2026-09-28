package com.ishitv.urlshortener.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ishitv.urlshortener.support.Http;
import com.ishitv.urlshortener.support.IntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * B6: with Redis unreachable, every endpoint still works from Postgres. (The Python service returned
 * 500 for redirects and deletes.) Redis points at a port with nothing listening.
 *
 * <p>{@code @AutoConfigureMetrics}: Spring Boot tests replace real metrics with no-ops by default.
 */
@AutoConfigureMetrics
class RedisDownIT extends IntegrationTest {

    @DynamicPropertySource
    static void unreachableRedis(DynamicPropertyRegistry registry) throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        // spring.data.redis.url wins over the host/port that IntegrationTest registers.
        registry.add("spring.data.redis.url", () -> "redis://localhost:" + closedPort);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry metrics;

    private Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
    }

    @Test
    void allEndpointsFallBackToTheDatabase() {
        Http.Response created = http.postJson("/shorten", "{\"original_url\":\"https://example.com/redis-down\"}");
        assertThat(created.status()).isEqualTo(201);
        String code = created.shortCode();

        Http.Response redirect = http.get("/" + code);
        assertThat(redirect.status()).isEqualTo(302);
        assertThat(redirect.header("Location")).hasValue("https://example.com/redis-down");

        assertThat(http.get("/stats/" + code).status()).isEqualTo(200);
        assertThat(http.delete("/" + code).status()).isEqualTo(204);
        assertThat(http.get("/" + code).status()).isEqualTo(404);

        // Guard against a vacuous pass: the fallbacks must really have happened.
        assertThat(metrics.find("urlshortener.cache.errors").counters()).isNotEmpty();
    }

    @Test
    void failuresAreCountedSoTheyCanBeAlertedOn() {
        http.get("/stats/AnyCode123");

        assertThat(metrics.find("urlshortener.cache.errors").counters())
                .isNotEmpty()
                .allSatisfy(counter -> assertThat(counter.count()).isPositive());
    }
}
