package com.ishitv.urlshortener.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for full-application integration tests against real Postgres.
 * Primary, replica and flush pools all point at the same container here (like docker compose does), so
 * reads see writes immediately. {@code ReadWriteRoutingIT} overrides the replica with a separate database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        for (String pool : new String[] {"primary", "replica", "flush"}) {
            registry.add("app.datasource." + pool + ".jdbc-url", Containers.POSTGRES::getJdbcUrl);
            registry.add("app.datasource." + pool + ".username", Containers.POSTGRES::getUsername);
            registry.add("app.datasource." + pool + ".password", Containers.POSTGRES::getPassword);
        }
    }
}
