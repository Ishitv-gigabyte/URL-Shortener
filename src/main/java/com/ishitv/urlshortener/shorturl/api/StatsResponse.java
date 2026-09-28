package com.ishitv.urlshortener.shorturl.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response of {@code GET /stats/{code}}. Field order matches the Python response. */
public record StatsResponse(
        @JsonProperty("clicks") long clicks,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("original_url") String originalUrl) {
}
