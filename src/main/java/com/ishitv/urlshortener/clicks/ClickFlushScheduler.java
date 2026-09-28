package com.ishitv.urlshortener.clicks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Runs {@link ClickFlushJob} on a fixed delay: the next run starts {@code flush-interval} after the previous
 * one <em>finished</em>, so a slow flush never overlaps itself on this instance. (Other instances may flush
 * at the same time; the job is designed for that.)
 *
 * <p>Separate from the job so tests can disable the timer and call {@code flushOnce()} deterministically.
 */
@Component
@EnableScheduling
@ConditionalOnBooleanProperty(name = "app.clicks.scheduling-enabled", matchIfMissing = true)
public class ClickFlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClickFlushScheduler.class);

    private final ClickFlushJob job;
    private final MeterRegistry metrics;

    public ClickFlushScheduler(ClickFlushJob job, MeterRegistry metrics) {
        this.job = job;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.clicks.flush-interval}")
    public void flush() {
        try {
            job.flushOnce();
        } catch (RuntimeException e) {
            // Unlike the Python thread (B7), a failure never stops future runs; the batch stays in Redis
            // and is retried as an orphan.
            metrics.counter("urlshortener.clicks.flush.failures").increment();
            log.error("Click flush failed; pending clicks remain in Redis and will be retried", e);
        }
    }
}
