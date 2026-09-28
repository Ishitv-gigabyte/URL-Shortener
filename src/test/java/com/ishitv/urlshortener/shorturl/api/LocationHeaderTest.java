package com.ishitv.urlshortener.shorturl.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Expected values produced by Python's urllib.parse.quote with Starlette's safe set. */
class LocationHeaderTest {

    @Test
    void leavesOrdinaryUrlsUntouched() {
        assertThat(LocationHeader.encode("https://example.com/path?a=1&b=2#top"))
                .isEqualTo("https://example.com/path?a=1&b=2#top");
    }

    @Test
    void encodesSpacesNonAsciiAndUnsafeCharacters() {
        assertThat(LocationHeader.encode("https://example.com/a b/ü?q=1&x=<y>#frag|%41"))
                .isEqualTo("https://example.com/a%20b/%C3%BC?q=1&x=%3Cy%3E#frag%7C%41");
    }

    @Test
    void keepsPythonsAlwaysSafeCharacters() {
        assertThat(LocationHeader.encode("https://ex-a.com/_~.;,+*()'!$@[]")).isEqualTo("https://ex-a.com/_~.;,+*()'!$@[]");
    }
}
