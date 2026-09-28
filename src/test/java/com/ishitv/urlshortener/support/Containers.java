package com.ishitv.urlshortener.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Singleton containers: started once per JVM, shared by every integration test, stopped by
 * Testcontainers' Ryuk sidecar when the JVM exits. Starting Postgres per test class would add
 * seconds to every class for no extra isolation (tests clean their own rows).
 */
public final class Containers {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("urlshortener");

    /** A second, independent database used as a "replica" that only receives rows we copy into it. */
    public static final PostgreSQLContainer POSTGRES_REPLICA = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("urlshortener");

    static {
        POSTGRES.start();
    }

    private Containers() {
    }

    public static synchronized void startReplica() {
        if (!POSTGRES_REPLICA.isRunning()) {
            POSTGRES_REPLICA.start();
        }
    }
}
