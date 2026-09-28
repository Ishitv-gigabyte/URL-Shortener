package com.ishitv.urlshortener.config;

import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends read-only transactions to the replica and everything else to the primary.
 *
 * <p>The routing key is read when a physical connection is requested. This class must therefore sit
 * behind a {@link org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy}: the JPA
 * transaction manager asks for a connection while <em>beginning</em> the transaction, before it has
 * published the read-only flag. Without the lazy proxy, every transaction would see
 * {@code readOnly == false} here and all reads would silently go to the primary.
 */
public class ReplicaRoutingDataSource extends AbstractRoutingDataSource {

    public enum Route { PRIMARY, REPLICA }

    public ReplicaRoutingDataSource(DataSource primary, DataSource replica) {
        setTargetDataSources(Map.of(Route.PRIMARY, primary, Route.REPLICA, replica));
        // No transaction at all (e.g. Hibernate reading metadata at startup) → primary.
        setDefaultTargetDataSource(primary);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? Route.REPLICA : Route.PRIMARY;
    }
}
