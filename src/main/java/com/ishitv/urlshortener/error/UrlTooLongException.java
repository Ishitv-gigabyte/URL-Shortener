package com.ishitv.urlshortener.error;

/**
 * The submitted URL passed the 1500-character check but exceeds it once "https://" is prepended.
 * Rendered as a 422 in the same shape as the other validation errors (the Python service returned 500).
 */
public class UrlTooLongException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String submittedUrl;

    public UrlTooLongException(String submittedUrl) {
        super("URL exceeds the maximum length after normalisation");
        this.submittedUrl = submittedUrl;
    }

    public String getSubmittedUrl() {
        return submittedUrl;
    }
}
