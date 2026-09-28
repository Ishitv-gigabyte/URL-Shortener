package com.ishitv.urlshortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Typed view of the {@code app.*} settings in application.yml. Bound and validated once at startup,
 * so a missing or malformed value stops the app instead of failing on the first request.
 *
 * @param baseUrl prefix for {@code short_url} in responses, e.g. {@code https://sho.rt}
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(@NotBlank String baseUrl) {
}
