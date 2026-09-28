package com.ishitv.urlshortener.shorturl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * created_at handling that matches the Python service byte-for-byte.
 */
public final class Timestamps {

    private static final DateTimeFormatter TO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private Timestamps() {
    }

    /** UTC now at Postgres precision (microseconds), so the value we return equals the value we stored. */
    public static LocalDateTime nowUtc(Clock clock) {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Pydantic's format: {@code 2026-01-02T03:04:05.120000}, or {@code 2026-01-02T03:04:05} when the
     * microseconds are zero. Jackson's default would print {@code .12}, hence this explicit formatter.
     */
    public static String format(LocalDateTime timestamp) {
        String seconds = timestamp.format(TO_SECONDS);
        int micros = timestamp.getNano() / 1_000;
        return micros == 0 ? seconds : seconds + String.format(".%06d", micros);
    }
}
