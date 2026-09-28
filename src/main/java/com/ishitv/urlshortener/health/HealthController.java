package com.ishitv.urlshortener.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Load-balancer liveness probe. Deliberately static: it reports that the JVM is serving HTTP,
 * not that Postgres or Redis are reachable. Dependency health lives at /actuator/health.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok");
    }
}
