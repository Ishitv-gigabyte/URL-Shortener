package com.ishitv.urlshortener.shorturl.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ishitv.urlshortener.config.AppProperties;
import com.ishitv.urlshortener.shorturl.ShortUrlService;
import com.ishitv.urlshortener.shorturl.ShortenedUrl;
import com.ishitv.urlshortener.shorturl.Timestamps;
import com.ishitv.urlshortener.shorturl.UrlStats;

import jakarta.validation.Valid;

/**
 * HTTP layer only: parse, delegate, map to response DTOs. Paths and status codes are the Python
 * service's contract. Literal paths ({@code /health}, {@code /shorten}, {@code /docs}) win over
 * {@code /{shortCode}} because Spring ranks literal patterns above variables.
 */
@RestController
public class ShortUrlController {

    private final ShortUrlService service;
    private final AppProperties properties;

    public ShortUrlController(ShortUrlService service, AppProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/shorten")
    public ResponseEntity<ShortUrlResponse> shorten(@Valid @RequestBody ShortenRequest request) {
        ShortenedUrl shortened = service.shorten(request.originalUrl());
        ShortUrlResponse body = new ShortUrlResponse(
                properties.baseUrl() + "/" + shortened.shortCode(),
                Timestamps.format(shortened.createdAt()),
                shortened.originalUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String url = service.resolve(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, LocationHeader.encode(url))
                .build();
    }

    @GetMapping("/stats/{shortCode}")
    public StatsResponse stats(@PathVariable String shortCode) {
        UrlStats stats = service.stats(shortCode);
        return new StatsResponse(stats.clicks(), Timestamps.format(stats.createdAt()), stats.originalUrl());
    }

    @DeleteMapping("/{shortCode}")
    public ResponseEntity<Void> delete(@PathVariable String shortCode) {
        service.delete(shortCode);
        return ResponseEntity.noContent().build();
    }
}
