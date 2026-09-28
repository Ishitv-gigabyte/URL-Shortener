package com.ishitv.urlshortener.shorturl;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

import org.springframework.stereotype.Component;

/**
 * Generates random 10-character short codes over the base62 alphabet, the same format as the Python
 * service ({@code random.choices(string.ascii_letters + string.digits, k=10)}).
 *
 * <p>This is random generation, not base62 <em>encoding</em> of a database ID: sequential IDs would make
 * every link enumerable. The keyspace is 62^10 ≈ 8.4 × 10^17, so collisions are handled by retrying
 * on the unique constraint rather than by coordination.
 */
@Component
public class ShortCodeGenerator {

    public static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    public static final int LENGTH = 10;

    private final RandomGenerator random;

    public ShortCodeGenerator() {
        // SecureRandom (unlike Python's random / java.util.Random) is unpredictable from earlier outputs.
        this(new SecureRandom());
    }

    ShortCodeGenerator(RandomGenerator random) {
        this.random = random;
    }

    public String next() {
        char[] code = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            code[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(code);
    }

    /** Anything else can't be a code we issued; rejecting it early also keeps it out of Redis key names. */
    public static boolean isWellFormed(String candidate) {
        return candidate != null && candidate.matches("[A-Za-z0-9]{1,32}");
    }
}
