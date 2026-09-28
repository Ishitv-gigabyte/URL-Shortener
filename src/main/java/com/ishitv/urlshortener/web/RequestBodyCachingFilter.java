package com.ishitv.urlshortener.web;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Keeps a copy of the {@code POST /shorten} body as Jackson reads it. A request body is a one-shot
 * stream; without this, the error handler could not echo the body back in a 422 ("input" field).
 */
@Component
public class RequestBodyCachingFilter extends OncePerRequestFilter {

    /** original_url is at most 1500 chars; anything past this isn't needed for an error message. */
    private static final int CACHE_LIMIT_BYTES = 16 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/shorten".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new ContentCachingRequestWrapper(request, CACHE_LIMIT_BYTES), response);
    }
}
