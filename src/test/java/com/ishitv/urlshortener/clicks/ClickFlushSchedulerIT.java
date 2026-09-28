package com.ishitv.urlshortener.clicks;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.ishitv.urlshortener.support.Http;
import com.ishitv.urlshortener.support.IntegrationTest;

/**
 * The @Scheduled wiring itself: with a short interval, clicks reach Postgres without a manual flush.
 *
 * <p>{@code @DirtiesContext}: Spring caches test contexts between classes. Without closing this one, its
 * 200ms flusher would keep running and flush other tests' pending clicks under their assertions.
 */
@DirtiesContext
@TestPropertySource(properties = {
        "app.clicks.scheduling-enabled=true",
        "app.clicks.flush-interval=200ms"
})
class ClickFlushSchedulerIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("primaryDataSource")
    private DataSource primary;

    @Test
    void scheduledFlushWritesClicksToPostgres() throws InterruptedException {
        Http http = new Http(port);
        String code = http.postJson("/shorten", "{\"original_url\":\"https://example.com/scheduled\"}").shortCode();
        for (int i = 0; i < 3; i++) {
            http.get("/" + code);
        }

        JdbcTemplate db = new JdbcTemplate(primary);
        int clicks = 0;
        for (int attempt = 0; attempt < 50 && clicks < 3; attempt++) {
            Thread.sleep(100);
            clicks = db.queryForObject("select clicks from codes where short_code_chars = ?", Integer.class, code);
        }

        assertThat(clicks).isEqualTo(3);
    }
}
