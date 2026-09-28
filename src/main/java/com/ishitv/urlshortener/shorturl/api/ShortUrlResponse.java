package com.ishitv.urlshortener.shorturl.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response of {@code POST /shorten}. Field order matches the Python response. */
public record ShortUrlResponse(
        @JsonProperty("short_url") String shortUrl,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("original_url") String originalUrl) {
}
