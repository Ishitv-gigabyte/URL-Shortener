package com.ishitv.urlshortener.cache;

import java.time.LocalDateTime;

/** Contents of one {@code code:{X}} hash. */
public record CachedUrl(String originalUrl, LocalDateTime createdAt, long clicks) {
}
