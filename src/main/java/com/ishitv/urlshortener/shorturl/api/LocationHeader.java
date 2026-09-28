package com.ishitv.urlshortener.shorturl.api;

import java.nio.charset.StandardCharsets;

/**
 * Percent-encodes a stored URL for the {@code Location} header exactly like Starlette's RedirectResponse:
 * {@code urllib.parse.quote(url, safe=":/%#?=@[]!$&'()*+,;")}.
 *
 * <p>Stored URLs are not validated and may contain spaces or non-ASCII characters, which are illegal in
 * a header. {@code java.net.URI} would throw on them, so the encoding is done by hand.
 */
public final class LocationHeader {

    private static final String SAFE = ":/%#?=@[]!$&'()*+,;" + "_.-~";

    private LocationHeader() {
    }

    public static String encode(String url) {
        StringBuilder out = new StringBuilder(url.length());
        for (byte b : url.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isAsciiLetterOrDigit(c) || (c < 128 && SAFE.indexOf(c) >= 0)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static boolean isAsciiLetterOrDigit(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
