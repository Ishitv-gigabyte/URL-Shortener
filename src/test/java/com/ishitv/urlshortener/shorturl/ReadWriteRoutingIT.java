package com.ishitv.urlshortener.shorturl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ishitv.urlshortener.support.Containers;

/**
 * Primary and replica are two unconnected Postgres containers, so "replication" only happens when a
 * test copies a row across. That makes routing observable: a row that exists only on the primary is
 * invisible to anything that was routed to the replica. Ports tests/test_read_replica.py.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReadWriteRoutingIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.startReplica();
        Flyway.configure()
                .dataSource(Containers.POSTGRES_REPLICA.getJdbcUrl(), Containers.POSTGRES_REPLICA.getUsername(),
                        Containers.POSTGRES_REPLICA.getPassword())
                .load()
                .migrate();

        for (String pool : new String[] {"primary", "flush"}) {
            registry.add("app.datasource." + pool + ".jdbc-url", Containers.POSTGRES::getJdbcUrl);
            registry.add("app.datasource." + pool + ".username", Containers.POSTGRES::getUsername);
            registry.add("app.datasource." + pool + ".password", Containers.POSTGRES::getPassword);
        }
        registry.add("app.datasource.replica.jdbc-url", Containers.POSTGRES_REPLICA::getJdbcUrl);
        registry.add("app.datasource.replica.username", Containers.POSTGRES_REPLICA::getUsername);
        registry.add("app.datasource.replica.password", Containers.POSTGRES_REPLICA::getPassword);
    }

    @Autowired
    private ShortCodeRepository repository;

    @Autowired
    @Qualifier("primaryDataSource")
    private DataSource primaryDataSource;

    @Autowired
    @Qualifier("replicaDataSource")
    private DataSource replicaDataSource;

    private JdbcTemplate primaryJdbc;
    private JdbcTemplate replicaJdbc;
    private String code;

    @BeforeEach
    void setUp() {
        primaryJdbc = new JdbcTemplate(primaryDataSource);
        replicaJdbc = new JdbcTemplate(replicaDataSource);
        code = UUID.randomUUID().toString().substring(0, 10);
    }

    @Test
    void saveWritesToPrimaryOnly() {
        repository.save(new ShortCode(code, "https://example.com", now()));

        assertThat(countOn(primaryJdbc)).isEqualTo(1);
        assertThat(countOn(replicaJdbc)).isZero();
    }

    @Test
    void readOnlyLookupIsServedByReplica() {
        repository.save(new ShortCode(code, "https://example.com", now()));

        // Not replicated yet: the replica-routed lookup can't see it, the primary-routed one can.
        assertThat(repository.findByShortCode(code)).isEmpty();
        assertThat(repository.findByShortCodeOnPrimary(code)).isPresent();

        replicate();

        assertThat(repository.findByShortCode(code))
                .get().extracting(ShortCode::getOriginalUrl).isEqualTo("https://example.com");
    }

    @Test
    void deleteGoesToPrimaryAndReplicaKeepsRowUntilReplicated() {
        repository.save(new ShortCode(code, "https://example.com", now()));
        replicate();

        assertThat(repository.deleteByShortCode(code)).isEqualTo(1);

        assertThat(countOn(primaryJdbc)).isZero();
        assertThat(repository.findByShortCode(code)).isPresent(); // stale replica read
    }

    @Test
    void replicaPoolRefusesWrites() {
        assertThatThrownBy(() -> replicaJdbc.update(
                "insert into codes (clicks, short_code_chars, original_url, created_at) values (0, ?, 'x', now())",
                code))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("read-only");
    }

    private int countOn(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select count(*) from codes where short_code_chars = ?", Integer.class, code);
    }

    /** Stand-in for WAL streaming replication: copy the primary's row to the replica. */
    private void replicate() {
        var row = primaryJdbc.queryForMap("select * from codes where short_code_chars = ?", code);
        var replicationWriter = new JdbcTemplate(new DriverManagerDataSource(
                Containers.POSTGRES_REPLICA.getJdbcUrl(), Containers.POSTGRES_REPLICA.getUsername(),
                Containers.POSTGRES_REPLICA.getPassword()));
        replicationWriter.update(
                "insert into codes (id, clicks, short_code_chars, original_url, created_at) values (?,?,?,?,?)",
                row.get("id"), row.get("clicks"), row.get("short_code_chars"), row.get("original_url"),
                (Timestamp) row.get("created_at"));
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
