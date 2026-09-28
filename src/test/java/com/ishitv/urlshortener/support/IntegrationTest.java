package com.ishitv.urlshortener.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for full-application integration tests against real Postgres and Redis.
 * Primary and flush pools point at the shared container. The replica is deliberately <em>not</em> set here:
 * application-test.yml defaults it to the primary (like docker compose), and {@link SeparateReplica}
 * overrides it. If this class registered the replica URL too, it would win over a subclass's
 * {@code @DynamicPropertySource} and routing tests would silently read from the primary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        for (String pool : new String[] {"primary", "flush"}) {
            registry.add("app.datasource." + pool + ".jdbc-url", Containers.POSTGRES::getJdbcUrl);
            registry.add("app.datasource." + pool + ".username", Containers.POSTGRES::getUsername);
            registry.add("app.datasource." + pool + ".password", Containers.POSTGRES::getPassword);
        }
        registry.add("spring.data.redis.host", Containers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> Containers.REDIS.getMappedPort(6379));
    }
}
