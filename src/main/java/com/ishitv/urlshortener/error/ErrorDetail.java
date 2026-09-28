package com.ishitv.urlshortener.error;

/** FastAPI's error body for non-validation errors: {@code {"detail": "URL not found"}}. */
public record ErrorDetail(String detail) {
}
