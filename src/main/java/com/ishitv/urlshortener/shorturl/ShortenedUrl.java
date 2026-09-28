package com.ishitv.urlshortener.shorturl;

import java.time.LocalDateTime;

/** Result of {@link ShortUrlService#shorten}: service-layer data, not the JSON shape. */
public record ShortenedUrl(String shortCode, String originalUrl, LocalDateTime createdAt) {
}
