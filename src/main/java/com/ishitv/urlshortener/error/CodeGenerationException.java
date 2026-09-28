package com.ishitv.urlshortener.error;

/** Rendered as 500 with the same detail message as the Python service. */
public class CodeGenerationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CodeGenerationException(int attempts) {
        super("Failed to generate unique short code after " + attempts + " attempts. Please try again.");
    }
}
