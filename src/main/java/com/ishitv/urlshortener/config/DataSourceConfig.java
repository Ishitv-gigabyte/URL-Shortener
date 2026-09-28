package com.ishitv.urlshortener.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * Three connection pools, mirroring the three SQLAlchemy engines of the Python service:
 *
 * <pre>
 *   primary  (Hikari) ─┐
 *                      ├─ ReplicaRoutingDataSource ─ LazyConnectionDataSourceProxy  ← JPA / repositories
 *   replica  (Hikari) ─┘
 *   flush    (Hikari) ────────────────────────────────────────────────────────────  ← ClickFlushJob (JdbcTemplate)
 * </pre>
 *
 * Pool sizes are set in application.yml, next to the reasoning for each number.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean
    @FlywayDataSource // migrations always run against the primary, never through the router
    @ConfigurationProperties("app.datasource.primary")
    public HikariDataSource primaryDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties("app.datasource.replica")
    public HikariDataSource replicaDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties("app.datasource.flush")
    public HikariDataSource flushDataSource() {
        return new HikariDataSource();
    }

    /** The DataSource JPA and Spring Data use. {@code @Primary} makes it the one injected by type. */
    @Bean
    @Primary
    public DataSource routingDataSource(@Qualifier("primaryDataSource") DataSource primary,
                                        @Qualifier("replicaDataSource") DataSource replica) {
        ReplicaRoutingDataSource router = new ReplicaRoutingDataSource(primary, replica);
        router.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(router);
    }

    @Bean
    public JdbcTemplate flushJdbcTemplate(@Qualifier("flushDataSource") DataSource flushDataSource) {
        return new JdbcTemplate(flushDataSource);
    }
}
