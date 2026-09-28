package com.ishitv.urlshortener.web;

import java.io.IOException;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * FastAPI answers {@code /stats/abc/} with a 307 to {@code /stats/abc}. Spring 6+ no longer matches a
 * trailing slash at all (it would 404), so this reproduces the redirect for paths that exist without it.
 */
@Component
public class TrailingSlashRedirectFilter extends OncePerRequestFilter {

    private static final Pattern ROUTABLE_WITHOUT_SLASH = Pattern.compile("^/(stats/)?[^/]+$");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.length() < 2 || !path.endsWith("/")
                || !ROUTABLE_WITHOUT_SLASH.matcher(path.substring(0, path.length() - 1)).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String target = ServletUriComponentsBuilder.fromRequest(request)
                .replacePath(path.substring(0, path.length() - 1))
                .build()
                .toUriString();
        response.setStatus(HttpStatus.TEMPORARY_REDIRECT.value());
        response.setHeader(HttpHeaders.LOCATION, target);
    }
}
