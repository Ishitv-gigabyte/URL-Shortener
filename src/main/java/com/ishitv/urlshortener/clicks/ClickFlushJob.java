package com.ishitv.urlshortener.clicks;

import java.sql.Array;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.ishitv.urlshortener.config.AppProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Moves pending click deltas from Redis into {@code codes.clicks}.
 *
 * <pre>
 *  1. claim     RENAME {clicks}:pending → {clicks}:flushing:&lt;epochMs&gt;-&lt;uuid&gt;   (atomic swap)
 *  2. read      HGETALL the batch
 *  3. apply     one transaction: record batch id (idempotency) + one bulk UPDATE
 *  4. release   DEL the batch
 * </pre>
 *
 * A crash between 3 and 4 leaves the batch in Redis; a later run re-processes it ("orphan"), and the
 * batch id already in click_flush_batches stops it being applied twice. A crash before 3 commits leaves
 * it in Redis too, and the retry applies it. Either way every click is applied exactly once.
 */
@Component
public class ClickFlushJob {

    static final String FLUSHING_PREFIX = "{clicks}:flushing:";

    private static final Logger log = LoggerFactory.getLogger(ClickFlushJob.class);

    /** RENAME fails on a missing source key, so check first; both keys share the {clicks} slot. */
    private static final RedisScript<Long> CLAIM = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('RENAME', KEYS[1], KEYS[2])
            return 1
            """, Long.class);

    private static final String RECORD_BATCH =
            "INSERT INTO click_flush_batches (batch_id) VALUES (?) ON CONFLICT (batch_id) DO NOTHING";

    /**
     * One statement for the whole batch. Rows are locked in id order first: two instances flushing
     * overlapping codes at the same moment then queue behind each other instead of deadlocking.
     */
    private static final String APPLY_DELTAS = """
            WITH delta AS (
                SELECT code, clicks FROM unnest(?::varchar[], ?::int[]) AS d(code, clicks)
            ), locked AS (
                SELECT c.id FROM codes c JOIN delta ON c.short_code_chars = delta.code
                ORDER BY c.id
                FOR UPDATE OF c
            )
            UPDATE codes c SET clicks = c.clicks + delta.clicks
            FROM delta
            WHERE c.short_code_chars = delta.code
            """;

    private static final String PRUNE_BATCHES =
            "DELETE FROM click_flush_batches WHERE applied_at < now() - make_interval(secs => ?)";

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AppProperties.Clicks settings;
    private final Clock clock;
    private final MeterRegistry metrics;

    public ClickFlushJob(StringRedisTemplate redis,
                         @Qualifier("flushJdbcTemplate") JdbcTemplate jdbc,
                         @Qualifier("flushDataSource") DataSource flushDataSource,
                         AppProperties properties,
                         Clock clock,
                         MeterRegistry metrics) {
        this.redis = redis;
        this.jdbc = jdbc;
        // A local transaction manager for the flush pool, not a bean: a second PlatformTransactionManager
        // bean would make every plain @Transactional ambiguous and switch off Boot's JPA manager.
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(flushDataSource));
        this.settings = properties.clicks();
        this.clock = clock;
        this.metrics = metrics;
    }

    /** One full flush cycle. Safe to run concurrently on several instances. */
    public void flushOnce() {
        Timer.Sample sample = Timer.start(metrics);
        String batchId = clock.millis() + "-" + UUID.randomUUID();
        String batchKey = FLUSHING_PREFIX + batchId;

        Long claimed = redis.execute(CLAIM, List.of(ClickCounter.PENDING_KEY, batchKey));
        if (claimed != null && claimed == 1) {
            apply(batchId, batchKey);
        }
        for (String orphan : findOrphans()) {
            log.warn("Re-applying click batch left behind by an earlier run: {}", orphan);
            metrics.counter("urlshortener.clicks.flush.orphans").increment();
            apply(orphan.substring(FLUSHING_PREFIX.length()), orphan);
        }
        jdbc.update(PRUNE_BATCHES, settings.batchRetention().toSeconds());
        sample.stop(metrics.timer("urlshortener.clicks.flush"));
    }

    private void apply(String batchId, String batchKey) {
        Map<Object, Object> batch = redis.opsForHash().entries(batchKey);
        if (!batch.isEmpty()) {
            List<String> codes = new ArrayList<>(batch.size());
            List<Integer> deltas = new ArrayList<>(batch.size());
            batch.forEach((code, delta) -> {
                codes.add((String) code);
                deltas.add(Integer.parseInt((String) delta));
            });

            Boolean applied = transaction.execute(status -> {
                if (jdbc.update(RECORD_BATCH, batchId) == 0) {
                    return false; // this batch was already applied by an earlier (crashed) run
                }
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement(APPLY_DELTAS);
                    Array codeArray = connection.createArrayOf("varchar", codes.toArray());
                    Array deltaArray = connection.createArrayOf("int4", deltas.toArray());
                    statement.setArray(1, codeArray);
                    statement.setArray(2, deltaArray);
                    return statement;
                });
                return true;
            });

            if (Boolean.TRUE.equals(applied)) {
                long total = deltas.stream().mapToLong(Integer::longValue).sum();
                metrics.counter("urlshortener.clicks.flushed").increment(total);
                metrics.summary("urlshortener.clicks.flush.batch.codes").record(codes.size());
            }
        }
        // Only after the DB transaction committed (or the batch was found to be applied already).
        redis.delete(batchKey);
    }

    /** Batches whose timestamp is older than orphan-age belong to a run that died. */
    private List<String> findOrphans() {
        long cutoff = clock.millis() - settings.orphanAge().toMillis();
        List<String> orphans = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(FLUSHING_PREFIX + "*").count(100).build();
        try (Cursor<String> keys = redis.scan(options)) {
            keys.forEachRemaining(key -> {
                String id = key.substring(FLUSHING_PREFIX.length());
                long createdAt = Long.parseLong(id.substring(0, id.indexOf('-')));
                if (createdAt < cutoff) {
                    orphans.add(key);
                }
            });
        }
        return orphans;
    }
}
