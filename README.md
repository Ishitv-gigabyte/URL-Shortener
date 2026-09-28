# URL Shortener

![CI](https://github.com/Ishitv-gigabyte/URL-Shortener/actions/workflows/java-ci.yml/badge.svg)

A URL shortener built to practise system design: a PostgreSQL primary with a read replica, Redis as a cache and a click buffer, and a scheduled, idempotent batch job that moves click counts into the database.

**Stack:** Java 21 · Spring Boot 4.1 (Web MVC, Data JPA, Data Redis) · PostgreSQL 15 · Redis 7 · Flyway · HikariCP · Micrometer/Prometheus · JUnit + Testcontainers · Docker · GitHub Actions

---

## Architecture

```
                   ┌───────────────────────── Spring Boot app (×N) ─────────────────────────┐
  client ─ HTTP ─▶ │ ShortUrlController → ShortUrlService ─┬─ UrlCache ───────────┐          │
                   │                                       ├─ ClickCounter ───────┤  Redis   │
                   │                                       └─ ShortCodeRepository │          │
                   │                                            │                 │          │
                   │          readOnly tx ─▶ replica pool (30) ─┤                 │          │
                   │         read-write tx ─▶ primary pool (30) ┘                 │          │
                   │  ClickFlushJob (@Scheduled 30s) ─▶ flush pool (2) ─▶ primary │          │
                   └──────────────────────────────────────────────────────────────┴──────────┘
```

| Concern | Design |
| --- | --- |
| Reads vs writes | An `AbstractRoutingDataSource` sends `@Transactional(readOnly = true)` work to the replica and everything else to the primary. It sits behind a `LazyConnectionDataSourceProxy`, so the routing decision is made after the transaction's read-only flag is set. Three HikariCP pools (primary, replica, flush job) keep one kind of work from starving another. |
| Redirect cache | Cache-aside. There is one Redis hash per code with a 1h TTL ± 10% jitter. Atomic Lua scripts fill it, negative caching handles unknown codes, and tombstones block re-caching after a delete. Every Redis failure falls back to Postgres. |
| Click counting | Redis holds *deltas*; Postgres holds the truth. Every 30s the pending hash is swapped out atomically with `RENAME` and applied in one bulk `UPDATE`. A batch-ID ledger in the same transaction makes each batch apply exactly once. |
| Short codes | 10 random base62 characters from `SecureRandom`. A unique constraint guarantees uniqueness, and the service retries on collision. |
| Schema | Flyway migrations. Hibernate only validates the schema, and startup fails if the entity and the table disagree. |

### Redis keys

| Key | Type | TTL | Purpose |
| --- | --- | --- | --- |
| `code:{X}` | hash `url`, `created_at`, `clicks` | 1h ± 10% | Redirect lookup and stats |
| `miss:{X}` | string | 60s | "Code doesn't exist" / deleted-code tombstone |
| `rev:<sha256(url)>` | string → code | 24h | Returns the existing code when the same URL is shortened again |
| `{clicks}:pending` | hash code → delta | none | Clicks not yet written to Postgres |
| `{clicks}:flushing:<id>` | hash | none | A batch being written by the flush job |

The braces are Redis Cluster hash tags. Keys that are used together (`code:{X}` and `miss:{X}`; `pending` and `flushing:*`) always land on the same shard, which is required for multi-key Lua scripts and `RENAME`.

### What can be lost, and when

| Failure | Clicks lost |
| --- | --- |
| App instance crash | At most the click of the request in flight |
| Crash during a flush | None: the batch is re-applied, exactly once |
| Redis crash (AOF `everysec`) | About 1 second |
| Redis node lost without persistence or replica | Up to one flush interval (30s) |
| Redis unreachable | Clicks during the outage (redirects keep working; counted in `urlshortener.clicks.dropped`) |

`/stats` click counts are eventually consistent. The total in Postgres is exact once flushes complete.

---

## Why PostgreSQL

The data is one table: code → URL, plus a click count. Almost any database can store that. The choice comes down to what the rest of the design needs from it:

- **A hard uniqueness guarantee.** Codes are random, so collisions are resolved by a `UNIQUE` constraint plus a retry, not by coordination between instances.
- **Multi-statement ACID transactions.** A click batch is applied as "record the batch ID *and* add the deltas" in one transaction. That is what makes re-applying a batch after a crash safe (`INSERT … ON CONFLICT DO NOTHING`).
- **Bulk updates in one statement.** `UPDATE … FROM unnest(codes[], deltas[])` applies a whole batch in one round trip. `SELECT … ORDER BY id FOR UPDATE` locks rows in a fixed order so concurrent flushes can't deadlock.
- **Managed read replicas.** Streaming replication (e.g. RDS read replicas) supplies the replica that the read/write split routes to.
- **The database is not the hot path.** Redis serves redirects and absorbs click writes. Postgres sees inserts, cache misses and one batched `UPDATE` every 30 seconds, well within what a single primary handles.

**Alternatives:**

| Database | Verdict |
| --- | --- |
| MySQL | Would work equally well. The flush would use a different bulk-update idiom (no `unnest`), and uniqueness and replicas map directly. |
| DynamoDB / Cassandra | A natural fit for pure key lookups at very large scale or across regions. The costs: the flush's transactional batch ledger, lock ordering and read-replica routing would all need redesigning. Worth it only once write volume outgrows a single primary. |
| MongoDB | No advantage for a two-column, fixed-shape record. |
| Redis only | Fast, but its durability depends on persistence settings. Links must not disappear, so Redis is used as a cache and buffer, not the system of record. |

When one Postgres primary stops being enough, the next steps are range or hash partitioning of `codes` by code, then sharding by code hash.

---

## API

| Method | Path | Success | Errors |
| --- | --- | --- | --- |
| `POST` | `/shorten` | **201** `{"short_url", "created_at", "original_url"}` | **422** `{"detail": [...]}` validation errors; **500** after 10 code collisions |
| `GET` | `/{short_code}` | **302** with `Location` | **404** `{"detail": "URL not found"}` |
| `GET` | `/stats/{short_code}` | **200** `{"clicks", "created_at", "original_url"}` | **404** |
| `DELETE` | `/{short_code}` | **204** | **404** |
| `GET` | `/health` | **200** `{"status": "ok"}` | Static liveness check for the load balancer |

- Request body: `{"original_url": "<10–1500 chars>"}`. A URL without `http://` or `https://` gets `https://` prepended.
- `created_at` is UTC, formatted like `2026-09-28T17:10:48.108884`.
- Swagger UI: `/docs`. OpenAPI spec: `/openapi.json`. Metrics: `/actuator/prometheus`. Dependency health: `/actuator/health`.

```bash
curl -s -X POST localhost:8000/shorten -H 'content-type: application/json' \
     -d '{"original_url":"https://example.com/some/long/path"}'
# {"short_url":"http://localhost:8000/Ab3dE9xYz0","created_at":"2026-09-28T17:10:48.108884","original_url":"https://example.com/some/long/path"}

curl -si localhost:8000/Ab3dE9xYz0 | grep -i location   # Location: https://example.com/some/long/path
curl -s  localhost:8000/stats/Ab3dE9xYz0                # {"clicks":1,...}
```

---

## Running locally

**With Docker (recommended).** You need Docker Desktop or Colima.

```bash
docker compose up --build                   # app on http://localhost:8000
APP_PORT=18000 docker compose up --build    # if port 8000 is taken
docker compose down -v                      # stop and delete the data volumes
```

Compose starts `app`, `db` (Postgres 15) and `redis` (Redis 7 with AOF `everysec` and `maxmemory-policy volatile-lru`, so un-flushed clicks, which have no TTL, are never evicted). Only the app's port is published, so it doesn't clash with a Postgres or Redis already running on your machine.

**Without Docker for the app.** You need JDK 21 plus a running Postgres and Redis.

```bash
export DB_PRIMARY_URL=jdbc:postgresql://localhost:5432/urlshortener DB_USERNAME=postgres DB_PASSWORD=postgres
./mvnw spring-boot:run
```

### Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_PRIMARY_URL` | `jdbc:postgresql://localhost:5432/urlshortener` | Primary (writes, flush job, migrations) |
| `DB_REPLICA_URL` | = `DB_PRIMARY_URL` | Read replica (redirect/stats cache misses) |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / `postgres` | Both pools. `DB_REPLICA_USERNAME` and `DB_REPLICA_PASSWORD` override them for the replica. |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis |
| `BASE_URL` | `http://localhost:8000` | Prefix of `short_url` |
| `SERVER_PORT` | `8000` | HTTP port |

Pool sizes, TTLs and the flush interval are in [`application.yml`](src/main/resources/application.yml), each with its reasoning.

---

## Tests

```bash
./mvnw verify      # unit tests (*Test) + integration tests (*IT); needs Docker for Testcontainers
./mvnw test        # unit tests only, no Docker
```

Integration tests run against real Postgres and Redis containers:

| Area | What is proven |
| --- | --- |
| Read/write routing | With **two separate Postgres containers**, writes land on the primary, cache misses read the replica, and a row that hasn't "replicated" is invisible to reads |
| Cache consistency | One entry per code, TTLs and jitter, first-writer-wins seeding, tombstones stopping a stale replica read from re-caching a deleted code |
| Redis outage | Redis points at a closed port, and every endpoint still works from Postgres (and the test asserts that the fallback actually happened) |
| Click counting | Deltas reach Postgres, a lost cache entry never resets the total, a batch re-processed after a crash is not double-counted, and orphaned batches are recovered |
| API contract | Status codes, JSON bodies and validation error formats for every endpoint |
| Schema | Columns, types and indexes after Flyway migrations |

**Colima users:** Testcontainers needs two environment variables:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

CI ([`java-ci.yml`](.github/workflows/java-ci.yml)) runs `./mvnw verify`. It then builds the Compose stack, smoke-tests shorten → redirect → stats → delete, and runs `locustfile.py` for 15 seconds as a functional check.

---

## Load testing

No benchmark numbers are published yet; they will be added once measured reproducibly. `locustfile.py` models the traffic mix: 70% redirects, 20% shortens with unique URLs, 10% stats.

How to measure:

1. **Run the load generator on a different machine** from the app. On one laptop, Locust and the app compete for CPU, and localhost latency is unrealistically low.
2. Start the stack: `docker compose up --build -d`. For a realistic test, deploy it with a real read replica and a separate Redis.
3. Run a fixed, recorded profile headless and export CSVs:
   ```bash
   pip install locust
   locust -f locustfile.py --host http://<app-host>:8000 --headless \
          -u 500 -r 10 -t 8m --csv results/500u --html results/500u.html
   ```
4. Record the git commit, instance types, pool sizes, `-u`/`-r`/`-t`, and Redis/Postgres versions next to the CSVs.
5. While it runs, watch `/actuator/prometheus`: `http_server_requests_seconds` (p95/p99), `hikaricp_connections_pending`, `urlshortener_cache_lookups_total` (hit rate) and `urlshortener_clicks_flush_seconds`. Those tell you *why* a number is what it is.
6. Throw away the ramp-up period, and repeat each run at least three times before quoting a result.

---

## Project layout

```
src/main/java/com/ishitv/urlshortener/
  shorturl/        entity, repository, service, code generator
  shorturl/api/    controller, request/response records, Location encoding
  cache/           UrlCache: every Redis cache key, TTL and Lua script
  clicks/          ClickCounter, ClickFlushJob, ClickFlushScheduler
  config/          data sources + routing, typed app properties
  error/           exception → HTTP error response mapping
  web/             servlet filters (trailing-slash redirect, request body caching)
src/main/resources/db/migration/   Flyway SQL
```

## License

MIT
