package com.ishitv.urlshortener.shorturl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.ishitv.urlshortener.support.Http;
import com.ishitv.urlshortener.support.IntegrationTest;

/** End-to-end behaviour of the four endpoints. Ports TestPhase2..5 of tests/test_api_endpoints.py. */
class ShortUrlApiIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    private Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
    }

    @Test
    void shortenReturns201WithShortUrlAndOriginalUrl() {
        Http.Response response = http.postJson("/shorten", "{\"original_url\":\"https://example.com/very/long/path\"}");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body())
                .matches("\\{\"short_url\":\"http://localhost:8000/[A-Za-z0-9]{10}\","
                        + "\"created_at\":\"\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d{6})?\","
                        + "\"original_url\":\"https://example.com/very/long/path\"}");
    }

    @Test
    void shortenAddsHttpsWhenSchemeMissing() {
        Http.Response response = http.postJson("/shorten", "{\"original_url\":\"example.com/noscheme\"}");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).contains("\"original_url\":\"https://example.com/noscheme\"");
    }

    @Test
    void shortenRejectsEmptyUrl() {
        assertThat(http.postJson("/shorten", "{\"original_url\":\"\"}").status()).isEqualTo(422);
    }

    @Test
    void redirectReturns302ToOriginalUrl() {
        String code = http.postJson("/shorten", "{\"original_url\":\"https://google.com\"}").shortCode();

        Http.Response response = http.get("/" + code);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("Location")).hasValue("https://google.com");
        assertThat(response.body()).isEmpty();
    }

    @Test
    void redirectReturns404ForUnknownCode() {
        Http.Response response = http.get("/invalid123code");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("{\"detail\":\"URL not found\"}");
    }

    @Test
    void statsReturnsClicksCreatedAtAndOriginalUrl() {
        Http.Response created = http.postJson("/shorten", "{\"original_url\":\"https://example.com/stats\"}");
        String createdAt = created.body().replaceAll(".*\"created_at\":\"([^\"]+)\".*", "$1");

        Http.Response stats = http.get("/stats/" + created.shortCode());

        assertThat(stats.status()).isEqualTo(200);
        assertThat(stats.body()).isEqualTo(
                "{\"clicks\":0,\"created_at\":\"" + createdAt + "\",\"original_url\":\"https://example.com/stats\"}");
    }

    @Test
    void deleteRemovesUrl() {
        String code = http.postJson("/shorten", "{\"original_url\":\"https://example.com/delete\"}").shortCode();

        Http.Response deleted = http.delete("/" + code);

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();
        assertThat(http.get("/" + code).status()).isEqualTo(404);
        assertThat(http.get("/stats/" + code).status()).isEqualTo(404);
    }

    @Test
    void deleteReturns404ForUnknownCode() {
        Http.Response response = http.delete("/invalid123code");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("{\"detail\":\"URL not found\"}");
    }

    @Test
    void codesAreUniqueAndBase62() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String code = http.postJson("/shorten", "{\"original_url\":\"https://example.com/" + i + "\"}").shortCode();
            assertThat(code).matches("[A-Za-z0-9]{10}");
            assertThat(codes.add(code)).as("collision on %s", code).isTrue();
        }
    }

    @Test
    void handlesConcurrentCreates() throws Exception {
        List<Future<Http.Response>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 50; i++) {
                int n = i;
                futures.add(pool.submit(() ->
                        http.postJson("/shorten", "{\"original_url\":\"https://example.com/concurrent/" + n + "\"}")));
            }
            for (Future<Http.Response> future : futures) {
                assertThat(future.get().status()).isEqualTo(201);
            }
        }
    }

    @Test
    void redirectEncodesUnsafeCharactersLikeStarlette() {
        String code = http.postJson("/shorten",
                "{\"original_url\":\"https://example.com/a b/ü?q=1&x=<y>#frag|\"}").shortCode();

        assertThat(http.get("/" + code).header("Location"))
                .hasValue("https://example.com/a%20b/%C3%BC?q=1&x=%3Cy%3E#frag%7C");
    }

    @Test
    void swaggerUiIsServedAtDocs() {
        Http.Response docs = http.get("/docs");

        // springdoc redirects /docs to its UI page; a browser (and FastAPI's test client) follows it.
        assertThat(docs.status()).isIn(200, 302);
        assertThat(http.get("/openapi.json").body()).contains("\"/shorten\"");
    }
}
