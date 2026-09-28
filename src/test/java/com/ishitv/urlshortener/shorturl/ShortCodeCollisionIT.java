package com.ishitv.urlshortener.shorturl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.ishitv.urlshortener.support.Http;
import com.ishitv.urlshortener.support.IntegrationTest;

/**
 * Forces the unique-constraint path that random 62^10 codes practically never hit. Each attempt is its
 * own transaction, so a failed INSERT doesn't poison the next attempt.
 */
@Import(ShortCodeCollisionIT.ScriptedGeneratorConfig.class)
class ShortCodeCollisionIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ScriptedGenerator generator;

    @BeforeEach
    void seedExistingCode() {
        generator.script("TAKENCODE1");
        new Http(port).postJson("/shorten", "{\"original_url\":\"https://example.com/first\"}");
    }

    @Test
    void retriesAfterACollisionAndSucceeds() {
        generator.script("TAKENCODE1", "TAKENCODE1", "FRESHCODE" + System.nanoTime() % 10);

        Http.Response response = new Http(port).postJson("/shorten", "{\"original_url\":\"https://example.com/second\"}");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.shortCode()).startsWith("FRESHCODE");
    }

    @Test
    void givesUpAfterTenCollisions() {
        generator.script("TAKENCODE1", "TAKENCODE1", "TAKENCODE1", "TAKENCODE1", "TAKENCODE1",
                "TAKENCODE1", "TAKENCODE1", "TAKENCODE1", "TAKENCODE1", "TAKENCODE1");

        Http.Response response = new Http(port).postJson("/shorten", "{\"original_url\":\"https://example.com/third\"}");

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(
                "{\"detail\":\"Failed to generate unique short code after 10 attempts. Please try again.\"}");
    }

    static class ScriptedGenerator extends ShortCodeGenerator {
        private final Deque<String> codes = new ArrayDeque<>();

        synchronized void script(String... next) {
            codes.clear();
            codes.addAll(java.util.List.of(next));
        }

        @Override
        public synchronized String next() {
            return codes.isEmpty() ? super.next() : codes.poll();
        }
    }

    @TestConfiguration
    static class ScriptedGeneratorConfig {
        @Bean
        @Primary
        ScriptedGenerator scriptedGenerator() {
            return new ScriptedGenerator();
        }
    }
}
