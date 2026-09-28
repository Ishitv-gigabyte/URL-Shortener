package com.ishitv.urlshortener.error;

/** Rendered as 404 {"detail": "URL not found"}. */
public class ShortCodeNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ShortCodeNotFoundException(String shortCode) {
        super("No URL for short code " + shortCode);
    }
}
