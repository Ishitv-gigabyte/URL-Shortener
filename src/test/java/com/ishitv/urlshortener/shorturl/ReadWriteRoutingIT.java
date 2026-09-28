package com.ishitv.urlshortener.shorturl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ishitv.urlshortener.support.IntegrationTest;
import com.ishitv.urlshortener.support.SeparateReplica;

/**
 * Repository-level routing. Primary and replica are two unconnected Postgres containers, so a row that
 * exists only on the primary is invisible to anything routed to the replica. Ports the concept tests of
 * tests/test_read_replica.py.
 */
class ReadWriteRoutingIT extends IntegrationTest {

    @DynamicPropertySource
    static void separateReplica(DynamicPropertyRegistry registry) {
        SeparateReplica.register(registry);
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
        code = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
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

        SeparateReplica.replicate(primaryJdbc, code);

        assertThat(repository.findByShortCode(code))
                .get().extracting(ShortCode::getOriginalUrl).isEqualTo("https://example.com");
    }

    @Test
    void deleteGoesToPrimaryAndReplicaKeepsRowUntilReplicated() {
        repository.save(new ShortCode(code, "https://example.com", now()));
        SeparateReplica.replicate(primaryJdbc, code);

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

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
