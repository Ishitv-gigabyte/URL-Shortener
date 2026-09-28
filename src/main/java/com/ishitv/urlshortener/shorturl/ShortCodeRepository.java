package com.ishitv.urlshortener.shorturl;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every method here opens its own transaction, and the transaction's read-only flag decides which
 * database serves it (see {@code ReplicaRoutingDataSource}):
 * <ul>
 *   <li>{@code readOnly = true}  → read replica</li>
 *   <li>{@code readOnly = false} → primary</li>
 * </ul>
 * The service layer is deliberately not {@code @Transactional}; if it were, these methods would join
 * the outer transaction and inherit <em>its</em> read-only flag, silently changing the routing.
 */
public interface ShortCodeRepository extends JpaRepository<ShortCode, Integer> {

    /** Redirect and stats lookups. May lag the primary by the replication delay. */
    @Transactional(readOnly = true)
    Optional<ShortCode> findByShortCode(String shortCode);

    /**
     * Same lookup, forced onto the primary for read-your-writes (shorten's de-dup path and delete).
     * The transaction is read-write purely so that it routes to the primary; it doesn't write.
     */
    @Transactional
    @Query("select c from ShortCode c where c.shortCode = :shortCode")
    Optional<ShortCode> findByShortCodeOnPrimary(@Param("shortCode") String shortCode);

    /** One DELETE statement; returns the number of rows removed (0 if a concurrent delete won). */
    @Transactional
    @Modifying
    @Query("delete from ShortCode c where c.shortCode = :shortCode")
    int deleteByShortCode(@Param("shortCode") String shortCode);
}
