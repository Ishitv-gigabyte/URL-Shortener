package com.ishitv.urlshortener.shorturl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    void alphabetIsExactlyPythonsAsciiLettersPlusDigits() {
        assertThat(ShortCodeGenerator.ALPHABET)
                .hasSize(62)
                .isEqualTo("abcdefghijklmnopqrstuvwxyz" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "0123456789");
    }

    @Test
    void codesAreTenBase62Characters() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.next()).matches("[A-Za-z0-9]{10}");
        }
    }

    @Test
    void noCollisionsInALargeSample() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(generator.next())).isTrue();
        }
    }

    @Test
    void everyCharacterOfTheAlphabetIsUsedRoughlyUniformly() {
        ShortCodeGenerator seeded = new ShortCodeGenerator(new SplittableRandom(42));
        Map<Character, Integer> counts = new HashMap<>();
        int samples = 62_000;
        for (int i = 0; i < samples / ShortCodeGenerator.LENGTH; i++) {
            for (char c : seeded.next().toCharArray()) {
                counts.merge(c, 1, Integer::sum);
            }
        }
        assertThat(counts).hasSize(62);
        // Expected ~1000 per character; a biased generator (e.g. modulo bias on a byte) would drift.
        assertThat(counts.values()).allSatisfy(n -> assertThat(n).isBetween(850, 1150));
    }

    @Test
    void wellFormedCodesAreBase62Only() {
        assertThat(ShortCodeGenerator.isWellFormed("AbC123xYz0")).isTrue();
        assertThat(ShortCodeGenerator.isWellFormed("clicks:AbC123xYz0")).isFalse();
        assertThat(ShortCodeGenerator.isWellFormed("favicon.ico")).isFalse();
        assertThat(ShortCodeGenerator.isWellFormed("")).isFalse();
        assertThat(ShortCodeGenerator.isWellFormed("a".repeat(33))).isFalse();
    }
}
