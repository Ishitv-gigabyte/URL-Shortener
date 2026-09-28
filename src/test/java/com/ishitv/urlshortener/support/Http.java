package com.ishitv.urlshortener.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Optional;

/**
 * Minimal real-HTTP client for tests: never follows redirects and never throws on 4xx/5xx, so tests
 * can assert on exactly what a client (Locust, a browser, the ALB) would receive.
 */
public final class Http {

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final String baseUrl;

    public Http(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    public Response delete(String path) {
        return send(HttpRequest.newBuilder(uri(path)).DELETE());
    }

    public Response postJson(String path, String json) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json)));
    }

    public Response send(String method, String path, String contentType, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return send(builder);
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = client.send(request.build(), BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public record Response(int status, String body, HttpResponse<String> raw) {

        public Optional<String> header(String name) {
            return raw.headers().firstValue(name);
        }

        /** The code at the end of a {@code short_url}, e.g. {@code http://localhost:8000/AbC123xYz0}. */
        public String shortCode() {
            String marker = "\"short_url\":\"";
            int start = body.indexOf(marker) + marker.length();
            String shortUrl = body.substring(start, body.indexOf('"', start));
            return shortUrl.substring(shortUrl.lastIndexOf('/') + 1);
        }
    }
}
