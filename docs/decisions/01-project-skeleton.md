# 01: Project skeleton, health endpoint, CI

## What was built

- A Maven project at the repo root: `pom.xml`, the Maven wrapper (`mvnw`, `.mvn/`), and `src/`. It sits alongside the Python `app/` until Phase 7.
- `UrlShortenerApplication`, the Spring Boot entry point.
- `GET /health` returns `{"status":"ok"}` with status 200. The route and body are the same as FastAPI's.
- Spring Boot Actuator at `/actuator/health`, `/actuator/metrics` and `/actuator/prometheus`.
- `application.yml` with `server.port=${SERVER_PORT:8000}`, which keeps the port Locust and the ALB expect.
- The test split:
  - `*Test` runs under Surefire in the `test` phase. `HealthControllerTest` is a `@WebMvcTest` slice with no server and only the web layer.
  - `*IT` runs under Failsafe in the `integration-test` phase. `ApplicationIT` boots the whole app on a random port and calls it over real HTTP.
- `.github/workflows/java-ci.yml`, which runs `./mvnw -B verify`. It runs next to the existing Python workflow until Phase 7.

### Versions managed by Spring Boot 4.1.1

These are recorded because Boot 4 is a major-version jump from what most tutorials use.

| Library | Version | Note |
| --- | --- | --- |
| Spring Framework | 7.0.9 | |
| Tomcat | 11.0.24 | Servlet 6.1 |
| Jackson | **3.1.5** | New package `tools.jackson.*` (was `com.fasterxml.jackson.*`). Annotations such as `@JsonProperty` keep their old package. |
| JUnit Jupiter | **6.0.3** | The brief said "JUnit 5". Jupiter 6 is the same programming model (`@Test`, extensions) with Java 17+ as the baseline. Pinning 5.x would fight the Boot BOM, so the BOM's version is used. |
| Hibernate ORM | 7.4.5 | Used from Phase 2 |
| Flyway | 12.4.0 | Boot 4 needs `spring-boot-starter-flyway`, not just `flyway-core` |
| Lettuce | 7.5.2 | Redis client (Phase 4) |
| Testcontainers | 2.0.5 | Artifact names changed in 2.x, e.g. `testcontainers-postgresql` |
| PostgreSQL JDBC | 42.7.13 | |

Two Boot 4 details differ from older guides. Starters were split into modules, for example `spring-boot-starter-webmvc` (was `-web`) with a matching `spring-boot-starter-webmvc-test`. `@WebMvcTest` also moved to `org.springframework.boot.webmvc.test.autoconfigure`.

## Why this approach

- **The health endpoint is static.** The ALB uses `/health` to decide whether an instance should receive traffic. If it also checked Postgres and Redis, a Redis blip would mark **every** instance unhealthy at once, and the ALB would have nowhere to send traffic. That is a self-inflicted total outage. The app is designed (Phase 4) to degrade to the DB when Redis is down, so the instance really *is* able to serve. The richer dependency view lives at `/actuator/health`, for humans and dashboards.
- **Actuator and Prometheus from day one.** Every later decision doc has to answer "how would you detect it?", and the answer needs a real metric. Micrometer counters (cache hits, dropped clicks, flush lag) get added as the features are built.
- **Unit and integration tests are split.** `mvn test` stays fast (seconds, no Docker). `mvn verify` runs the slower, realistic tests. CI always runs `verify`.
- **The Maven wrapper is committed**, so anyone can build with only a JDK, and every machine uses the same Maven version (3.9.16).

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| Use `/actuator/health` as the ALB target | It changes the external contract (path and body), and it includes dependency checks. That brings back the correlated-failure problem above. |
| Gradle | The brief says Maven. Maven's fixed lifecycle (`test` → `integration-test` → `verify`) is also easier to explain than a Gradle build script. |
| Put the Java project in a `java/` subfolder | After Phase 7 the Java project *is* the repo. Putting it at the root now avoids a large "move everything" commit later. |
| Skip Failsafe and run everything under Surefire | One slow Testcontainers test would make every `mvn test` slow. The split is cheap. |
| Replace the Python workflow now | The Python code stays until Phase 7. Both CIs running shows nothing regressed on either side. |

## What breaks under load or failure, and how to detect it

- **A static health check hides dependency outages.** If Postgres is down, `/health` still returns 200, so the ALB keeps sending traffic. Requests on cache misses then fail. Detect this with `/actuator/health` (status `DOWN` with the failing component), the HTTP 5xx rate per endpoint (`http.server.requests` metric, tagged by `uri` and `status`), and alerts on the Hikari pool metrics added in Phase 2. This is deliberate: *liveness* and *readiness/dependency health* are separate signals.
- **A slow JVM start.** A Spring app takes seconds to start, where uvicorn took under a second. If the ALB health-check grace period is shorter than startup, new instances get killed in a loop during deploys and scale-outs. Detect this through ALB target health flapping, or containers restarting repeatedly in `docker compose ps`. Fix it with a longer grace period, CDS/AOT caching, or a smaller context.
- **Thread-pool saturation.** Tomcat defaults to 200 worker threads. Under a traffic spike, requests queue in the accept backlog and latency climbs before any errors appear. The same "latency rises while RPS stays flat" shape was measured for uvicorn in the Python era (docs/legacy-python). Detect it with the `tomcat.threads.busy` vs `tomcat.threads.config.max` metrics and the p95/p99 of `http.server.requests`.

## Interview questions

1. Why should a load balancer's health check *not* verify the database, and what failure does that choice risk hiding?
2. What is the difference between liveness and readiness, and how would you express each in Kubernetes vs an AWS ALB?
3. What does `@WebMvcTest` load that `@SpringBootTest` doesn't, and when would a slice test give you false confidence?
4. Spring Boot 4 moved to Jackson 3 and split its starters into modules. How would you upgrade a large Boot 3 service safely?
5. Tomcat gives each request its own thread. How does that compare with FastAPI's sync-handler thread pool and with Java 21 virtual threads, and where does the bottleneck actually move to?
