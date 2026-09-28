package com.ishitv.urlshortener.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Typed view of the {@code app.*} settings in application.yml. Bound and validated once at startup,
 * so a missing or malformed value stops the app instead of failing on the first request.
 *
 * @param baseUrl prefix for {@code short_url} in responses, e.g. {@code https://sho.rt}
 * @param cache   Redis cache TTLs
 * @param clicks  click counter flushing
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(@NotBlank String baseUrl, @Valid @NotNull Cache cache, @Valid @NotNull Clicks clicks) {

    public record Cache(
            @NotNull Duration urlTtl,
            @DecimalMin("0.0") @DecimalMax("0.5") double ttlJitter,
            @NotNull Duration reverseTtl,
            @NotNull Duration negativeTtl) {
    }

    public record Clicks(
            @NotNull Duration flushInterval,
            @NotNull Duration orphanAge,
            @NotNull Duration batchRetention,
            boolean schedulingEnabled) {
    }
}
