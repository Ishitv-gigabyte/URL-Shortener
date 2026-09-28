package com.ishitv.urlshortener.shorturl.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.ishitv.urlshortener.support.Http;
import com.ishitv.urlshortener.support.IntegrationTest;

/**
 * Every expected body here was recorded from the Python service
 * (src/test/resources/golden/python-responses.txt). Differences are listed in docs/decisions/03-*.md.
 */
class ErrorContractIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    private Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
    }

    @Test
    void emptyUrl() {
        assertUnprocessable(http.postJson("/shorten", "{\"original_url\":\"\"}"),
                "{\"detail\":[{\"type\":\"string_too_short\",\"loc\":[\"body\",\"original_url\"],"
                        + "\"msg\":\"String should have at least 10 characters\",\"input\":\"\",\"ctx\":{\"min_length\":10}}]}");
    }

    @Test
    void urlLongerThan1500() {
        String url = "https://" + "a".repeat(1493);
        assertUnprocessable(http.postJson("/shorten", "{\"original_url\":\"" + url + "\"}"),
                "{\"detail\":[{\"type\":\"string_too_long\",\"loc\":[\"body\",\"original_url\"],"
                        + "\"msg\":\"String should have at most 1500 characters\",\"input\":\"" + url + "\","
                        + "\"ctx\":{\"max_length\":1500}}]}");
    }

    @Test
    void urlTooLongOnlyAfterAddingScheme() {
        // B5: 1500 chars passes validation, 1508 after "https://" used to be a plain-text 500 from Postgres.
        String url = "a".repeat(1500);
        Http.Response response = http.postJson("/shorten", "{\"original_url\":\"" + url + "\"}");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body()).contains("\"type\":\"string_too_long\"");
    }

    @Test
    void numberInsteadOfString() {
        assertUnprocessable(http.postJson("/shorten", "{\"original_url\":1234567890123}"),
                "{\"detail\":[{\"type\":\"string_type\",\"loc\":[\"body\",\"original_url\"],"
                        + "\"msg\":\"Input should be a valid string\",\"input\":1234567890123}]}");
    }

    @Test
    void nullInsteadOfString() {
        assertUnprocessable(http.postJson("/shorten", "{\"original_url\":null}"),
                "{\"detail\":[{\"type\":\"string_type\",\"loc\":[\"body\",\"original_url\"],"
                        + "\"msg\":\"Input should be a valid string\",\"input\":null}]}");
    }

    @Test
    void objectInsteadOfString() {
        assertUnprocessable(http.postJson("/shorten", "{\"original_url\":{\"a\":1}}"),
                "{\"detail\":[{\"type\":\"string_type\",\"loc\":[\"body\",\"original_url\"],"
                        + "\"msg\":\"Input should be a valid string\",\"input\":{\"a\":1}}]}");
    }

    @Test
    void missingField() {
        assertUnprocessable(http.postJson("/shorten", "{\"other\":\"x\"}"),
                "{\"detail\":[{\"type\":\"missing\",\"loc\":[\"body\",\"original_url\"],"
                        + "\"msg\":\"Field required\",\"input\":{\"other\":\"x\"}}]}");
    }

    @Test
    void arrayBody() {
        assertUnprocessable(http.postJson("/shorten", "[1,2]"),
                "{\"detail\":[{\"type\":\"model_attributes_type\",\"loc\":[\"body\"],"
                        + "\"msg\":\"Input should be a valid dictionary or object to extract fields from\",\"input\":[1,2]}]}");
    }

    @Test
    void malformedJson() {
        // Python's ctx.error text comes from its json module ("Expecting value"); ours is generic.
        assertUnprocessable(http.postJson("/shorten", "{\"original_url\": "),
                "{\"detail\":[{\"type\":\"json_invalid\",\"loc\":[\"body\",17],\"msg\":\"JSON decode error\","
                        + "\"input\":{},\"ctx\":{\"error\":\"Invalid JSON\"}}]}");
    }

    @Test
    void noBody() {
        assertUnprocessable(http.send("POST", "/shorten", "application/json", null),
                "{\"detail\":[{\"type\":\"missing\",\"loc\":[\"body\"],\"msg\":\"Field required\",\"input\":null}]}");
    }

    @Test
    void nonJsonContentType() {
        assertUnprocessable(http.send("POST", "/shorten", "text/plain", "{\"original_url\":\"https://example.com/plain\"}"),
                "{\"detail\":[{\"type\":\"model_attributes_type\",\"loc\":[\"body\"],"
                        + "\"msg\":\"Input should be a valid dictionary or object to extract fields from\","
                        + "\"input\":\"{\\\"original_url\\\":\\\"https://example.com/plain\\\"}\"}]}");
    }

    @Test
    void unknownPath() {
        Http.Response response = http.get("/a/b");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("{\"detail\":\"Not Found\"}");
    }

    @Test
    void wrongMethod() {
        Http.Response response = http.send("PUT", "/shorten", null, null);

        assertThat(response.status()).isEqualTo(405);
        assertThat(response.body()).isEqualTo("{\"detail\":\"Method Not Allowed\"}");
        assertThat(response.header("Allow")).isPresent();
    }

    @Test
    void trailingSlashRedirects() {
        Http.Response response = http.get("/stats/abc/");

        assertThat(response.status()).isEqualTo(307);
        assertThat(response.header("Location")).hasValue("http://localhost:" + port + "/stats/abc");
    }

    @Test
    void unknownCodeOnEveryEndpoint() {
        for (Http.Response response : new Http.Response[] {
                http.get("/zzzzzzzzzz"), http.get("/stats/zzzzzzzzzz"), http.delete("/zzzzzzzzzz")}) {
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body()).isEqualTo("{\"detail\":\"URL not found\"}");
        }
    }

    @Test
    void malformedCodeIsNotFound() {
        // B4: "clicks:abc" used to be looked up as a raw Redis key and redirect to the click count.
        assertThat(http.get("/clicks:AbCdEf1234").status()).isEqualTo(404);
    }

    private static void assertUnprocessable(Http.Response response, String expectedBody) {
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.header("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
        assertThat(response.body()).isEqualTo(expectedBody);
    }
}
