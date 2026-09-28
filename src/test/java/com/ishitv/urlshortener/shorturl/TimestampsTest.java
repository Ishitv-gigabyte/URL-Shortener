package com.ishitv.urlshortener.shorturl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** Expected strings recorded from Pydantic (see golden/python-responses.txt). */
class TimestampsTest {

    @Test
    void sixFractionalDigitsWhenMicrosecondsArePresent() {
        assertThat(Timestamps.format(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 120_000_000)))
                .isEqualTo("2026-01-02T03:04:05.120000");
    }

    @Test
    void noFractionWhenMicrosecondsAreZero() {
        assertThat(Timestamps.format(LocalDateTime.of(2026, 1, 2, 3, 4, 5))).isEqualTo("2026-01-02T03:04:05");
    }

    @Test
    void nowIsUtcAndTruncatedToPostgresPrecision() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T17:10:48.108884321Z"), ZoneOffset.UTC);

        assertThat(Timestamps.nowUtc(clock)).isEqualTo(LocalDateTime.of(2026, 9, 28, 17, 10, 48, 108_884_000));
    }
}
