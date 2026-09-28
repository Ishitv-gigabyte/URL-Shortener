package com.ishitv.urlshortener.shorturl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.ishitv.urlshortener.error.UrlTooLongException;

class UrlNormalizationTest {

    @Test
    void keepsHttpAndHttps() {
        assertThat(ShortUrlService.normalize("http://example.com")).isEqualTo("http://example.com");
        assertThat(ShortUrlService.normalize("https://example.com")).isEqualTo("https://example.com");
    }

    @Test
    void prefixesHttpsOtherwise() {
        assertThat(ShortUrlService.normalize("example.com/path")).isEqualTo("https://example.com/path");
        // Same as Python: other schemes are not recognised, they get prefixed too.
        assertThat(ShortUrlService.normalize("ftp://example.com")).isEqualTo("https://ftp://example.com");
    }

    @Test
    void rejectsUrlThatOnlyExceedsTheLimitAfterPrefixing() {
        assertThat(ShortUrlService.normalize("https://" + "a".repeat(1492))).hasSize(1500);
        assertThatThrownBy(() -> ShortUrlService.normalize("a".repeat(1493)))
                .isInstanceOf(UrlTooLongException.class);
    }
}
