# 06: Tests, Docker, Compose, CI, README

## What was built

- **Test suite: 68 tests** (18 unit, 50 integration), all running against real Postgres 15 and Redis 7 via Testcontainers.

  | Python test file | Java equivalent |
  | --- | --- |
  | `test_api_endpoints.py` Phase 2–5 | `ShortUrlApiIT`, `ClickCountingIT` (rapid redirects, click increment), `ShortCodeCollisionIT` |
  | `test_api_endpoints.py` `TestShortenCaching` | `CacheAsideIT` |
  | `test_api_endpoints.py` Phase 1 (`.env` exists) | Dropped. It tested the developer's machine, not the app. Startup config validation (`AppProperties`) replaces it. |
  | `test_cache_integration.py` | Mostly tested Redis/fakeredis itself. What mattered (TTLs, keys) is in `CacheAsideIT` and `UrlCacheKeysTest`. |
  | `test_read_replica.py` concept tests | `ReadWriteRoutingIT` (repository level) |
  | `test_read_replica.py` app routing tests | `ApiRoutingIT` (HTTP level) |
  | (new) | `ErrorContractIT` (golden fixtures), `FlywaySchemaIT`, `RedisDownIT`, `ClickFlushSchedulerIT`, unit tests for code generation, timestamps, Location encoding, URL normalisation, key naming and TTL jitter |

- **Multi-stage `Dockerfile`:** a JDK build stage produces the jar, which is split into Spring Boot layers. The runtime stage uses `eclipse-temurin:21-jre`, runs as a non-root user, and sizes the heap with `-XX:MaxRAMPercentage=75`.
- **`docker-compose.yml`:** app + Postgres 15 + Redis 7. Redis runs with AOF (`everysec`) and `maxmemory-policy volatile-lru`, and the app has a health check.
- **CI (`java-ci.yml`):** `mvn verify` → Compose build → smoke test of all four endpoints → a 15s run of the **unchanged** `locustfile.py` (fails on any request error).
- **README rewritten.** Every Python-era throughput and latency figure is removed and replaced with "to be re-measured" plus a reproducible Locust procedure. The old screenshots and AWS doc moved to `docs/legacy-python/` with a caveat header.

**Verified locally before committing:**
- `docker compose up --build` came up healthy.
- A curl walk-through (shorten → 3 redirects → stats `clicks: 3` → scheduled flush wrote `clicks = 3` to Postgres within 12s → delete → 404) passed.
- Flyway history showed V1 and V2 applied.
- Locust ran with 0 failures. Those numbers are intentionally not recorded (one laptop, 20 users, 20 seconds).

## Why this approach

**Real dependencies in tests.** The Python tests ran on SQLite and fakeredis. They could not catch Postgres-specific behaviour (the unique-constraint error type, `unnest`, `FOR UPDATE`, read-only connections) or Redis-specific behaviour (Lua scripts, `RENAME`, TTL semantics, eviction policy). CI started real Postgres and Redis containers and then never used them. With Testcontainers, the thing being tested is the thing that runs in production.

**Singleton containers.** They start once per JVM (about 5s) and are shared by all test classes. Tests isolate themselves by using unique codes and URLs instead of wiping state.

**Two lessons the suite learned the hard way, now written into the test support code:**
1. **`@DynamicPropertySource` in a base class beats the same property in a subclass.** Twice, a test that should have exercised a special setup (Redis down; separate replica) silently ran against the normal setup and **passed vacuously**. The fixes:
   - `RedisDownIT` uses `spring.data.redis.url`, which outranks host/port, and asserts that fallback metrics were recorded.
   - The base class no longer sets the replica URL at all.

   Lesson: **a test for a failure mode must prove the failure mode happened.**
2. **Spring caches test contexts across classes.** A context with a 200ms scheduled flusher would keep running and flush other tests' data under their assertions. `@DirtiesContext` closes it.

**Layered image.** Dependencies (~100 MB) change rarely; application classes change on every commit. With Spring Boot's layer extraction, a code change rebuilds and pushes only the small last layer. The dependency download is also its own build layer, keyed on `pom.xml`.

**No tests in `docker build`.** The integration tests need a Docker daemon, which a build container doesn't have (Docker-in-Docker is possible but slow and privileged). CI runs `mvn verify` before building the image, so an image is only built from tested code.

**Only the app port is published in Compose.** The Python compose file also published 5432 and 6379, which collide with a local Postgres or Redis. The app reaches them over the Compose network. `APP_PORT` allows a different host port.

**Why no numbers in the README.** The published figures described a different runtime (uvicorn, a thread pool of ~40, SQLAlchemy), different infrastructure, and a locustfile that changed between runs. They were only available as screenshots. Quoting them for the Java service would be invented data. The README instead gives the procedure that makes a future number defensible: separate load-generator machine, fixed parameters, CSV export, recorded commit and environment, repeated runs, and the server-side metrics to watch.

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| H2 / embedded Redis for speed | Different SQL dialect and different Redis semantics, the same weakness as SQLite + fakeredis. |
| `@Container` per test class | Correct, but slower (restarts containers per class). Singletons are the documented Testcontainers pattern for suites. |
| Spring Boot `@ServiceConnection` | Less code, but hides which property is set. The explicit `@DynamicPropertySource` taught us the precedence lesson above. |
| Buildpacks (`spring-boot:build-image`) | Excellent images with no Dockerfile, but less transparent, and the brief asked for a multi-stage Dockerfile. |
| Distroless / Alpine runtime image | Smaller, but distroless has no shell for the health check and debugging, and Alpine's musl needs a musl JDK build. `temurin:21-jre` is the pragmatic default. |
| Spring Boot native image (GraalVM) | Millisecond startup and a smaller footprint, but long builds and reflection configuration. Worth it for scale-to-zero, not here. |

## What breaks under load or failure, and how to detect it

- **Container memory limits.** The heap is 75% of the container limit. Off-heap memory (thread stacks, Netty buffers in Lettuce, metaspace) must fit in the remaining 25%, or the kernel OOM-kills the JVM. Detect it with container restarts and exit code 137, and compare `jvm.memory.used` with the container limit.
- **Slow start vs health checks.** The JVM takes several seconds to start, so the Compose `start_period` is 20s. An ALB with a short grace period could kill instances during deploys. Detect it with target health flapping and repeated restarts.
- **Test flakiness from shared state.** A test that asserts on global state (e.g. the whole `{clicks}:pending` hash) could fail when another test adds entries. Tests assert on their own codes only. Detect it with intermittent CI failures, reproduced with `-Dsurefire.rerunFailingTestsCount` off.
- **Docker Hub rate limits in CI** can fail image pulls (postgres, redis, locust). Detect it with `toomanyrequests` in logs. The fix is a registry mirror or authenticated pulls.

## Interview questions

1. Your old tests passed on SQLite and fakeredis. Name three bugs in this codebase that such a setup could not have caught.
2. A test for "Redis is down" passed while Redis was actually up. How would you design failure-mode tests so they can't pass vacuously?
3. Explain Spring Boot's layered jars and how they interact with Docker's layer cache in CI/CD.
4. How would you produce a performance number for this service that you'd defend in a design review? What would you record, and which server-side metrics explain the result?
5. Why does the container use `MaxRAMPercentage` instead of `-Xmx`, and what else besides the heap consumes memory in a JVM container?
