package com.ishitv.urlshortener.shorturl;

import java.time.LocalDateTime;

/** Result of {@link ShortUrlService#stats}: service-layer data, not the JSON shape. */
public record UrlStats(long clicks, String originalUrl, LocalDateTime createdAt) {
}
