package com.ishitv.urlshortener.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.ishitv.urlshortener.config.AppProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class UrlCacheKeysTest {

    @Test
    void codeAndMissKeysShareAHashTagSoTheyLandOnTheSameClusterSlot() {
        assertThat(UrlCache.codeKey("AbC123xYz0")).isEqualTo("code:{AbC123xYz0}");
        assertThat(UrlCache.missKey("AbC123xYz0")).isEqualTo("miss:{AbC123xYz0}");
    }

    @Test
    void reverseKeyIsAFixedLengthHashOfTheUrl() {
        String key = UrlCache.reverseKey("https://example.com/" + "x".repeat(1400));

        assertThat(key).startsWith("rev:").hasSize(4 + 64);
        assertThat(UrlCache.reverseKey("https://a.example")).isEqualTo(UrlCache.reverseKey("https://a.example"))
                .isNotEqualTo(UrlCache.reverseKey("https://b.example"));
    }

    @Test
    void ttlIsJitteredWithinTenPercent() {
        AppProperties properties = new AppProperties("http://localhost:8000",
                new AppProperties.Cache(Duration.ofHours(1), 0.1, Duration.ofDays(1), Duration.ofSeconds(60)),
                new AppProperties.Clicks(Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofDays(1), false));
        UrlCache cache = new UrlCache(null, properties, new SimpleMeterRegistry());

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < 10_000; i++) {
            long ttl = cache.jitteredTtl().toSeconds();
            min = Math.min(min, ttl);
            max = Math.max(max, ttl);
        }
        assertThat(min).isGreaterThanOrEqualTo(3240);
        assertThat(max).isLessThanOrEqualTo(3960);
        assertThat(max - min).as("values are actually spread out").isGreaterThan(500);
    }
}
