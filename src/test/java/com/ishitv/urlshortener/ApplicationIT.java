package com.ishitv.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/**
 * Boots the full application on a random port and calls it over real HTTP,
 * the same way the ALB and Locust will.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ApplicationIT {

    @LocalServerPort
    private int port;

    @Test
    void healthIsServedOverHttp() {
        String body = RestClient.create("http://localhost:" + port)
                .get().uri("/health")
                .retrieve()
                .body(String.class);

        assertThat(body).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void actuatorHealthIsExposed() {
        String body = RestClient.create("http://localhost:" + port)
                .get().uri("/actuator/health")
                .retrieve()
                .body(String.class);

        assertThat(body).contains("\"status\":\"UP\"");
    }
}
