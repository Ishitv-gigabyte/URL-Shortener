package com.ishitv.urlshortener.support;

import java.sql.Timestamp;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Test setup where the replica pool points at a second, unconnected Postgres. Nothing reaches it unless a
 * test calls {@link #replicate}, which makes replication lag (and therefore routing) observable.
 */
public final class SeparateReplica {

    private SeparateReplica() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        Containers.startReplica();
        Flyway.configure()
                .dataSource(Containers.POSTGRES_REPLICA.getJdbcUrl(), Containers.POSTGRES_REPLICA.getUsername(),
                        Containers.POSTGRES_REPLICA.getPassword())
                .load()
                .migrate();

        registry.add("app.datasource.replica.jdbc-url", Containers.POSTGRES_REPLICA::getJdbcUrl);
        registry.add("app.datasource.replica.username", Containers.POSTGRES_REPLICA::getUsername);
        registry.add("app.datasource.replica.password", Containers.POSTGRES_REPLICA::getPassword);
    }

    /** Stand-in for WAL streaming replication: copy one row from the primary to the replica. */
    public static void replicate(JdbcTemplate primary, String code) {
        Map<String, Object> row = primary.queryForMap("select * from codes where short_code_chars = ?", code);
        replicaWriter().update(
                "insert into codes (id, clicks, short_code_chars, original_url, created_at) values (?,?,?,?,?)",
                row.get("id"), row.get("clicks"), row.get("short_code_chars"), row.get("original_url"),
                (Timestamp) row.get("created_at"));
    }

    /** A direct, writable connection to the replica container (the app's replica pool is read-only). */
    public static JdbcTemplate replicaWriter() {
        return new JdbcTemplate(new DriverManagerDataSource(Containers.POSTGRES_REPLICA.getJdbcUrl(),
                Containers.POSTGRES_REPLICA.getUsername(), Containers.POSTGRES_REPLICA.getPassword()));
    }
}
