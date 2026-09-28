package com.ishitv.urlshortener.shorturl;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of the {@code codes} table. This is a persistence type only; it is never serialized to JSON
 * (the API uses the records in {@code shorturl.dto}).
 */
@Entity
@Table(name = "codes")
public class ShortCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "clicks", nullable = false)
    private int clicks;

    @Column(name = "short_code_chars", nullable = false, unique = true)
    private String shortCode;

    @Column(name = "original_url", nullable = false, length = 1500)
    private String originalUrl;

    /** UTC wall-clock time, matching the Python service's TIMESTAMP WITHOUT TIME ZONE column. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected ShortCode() {
    }

    public ShortCode(String shortCode, String originalUrl, LocalDateTime createdAt) {
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.createdAt = createdAt;
        this.clicks = 0;
    }

    public Integer getId() {
        return id;
    }

    public int getClicks() {
        return clicks;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
