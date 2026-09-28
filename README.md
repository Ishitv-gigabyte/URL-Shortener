# URL Shortener

![CI](https://github.com/Ishitv-gigabyte/URL-Shortener/actions/workflows/java-ci.yml/badge.svg)

A URL shortener built to practise system design: a read/write-split PostgreSQL setup, Redis as a cache and a click buffer, and a scheduled, idempotent batch job that moves click counts into the database.

**Stack:** Java 21 · Spring Boot 4.1 (Web MVC, Data JPA, Data Redis) · PostgreSQL 15 · Redis 7 · Flyway · HikariCP · Micrometer/Prometheus · JUnit + Testcontainers · Docker · GitHub Actions

The service was first built in Python (FastAPI) and then migrated to Spring Boot with **the same HTTP API**: paths, status codes and JSON bodies are identical, verified against responses recorded from the Python service. The migration plan is in [MIGRATION_PLAN.md](MIGRATION_PLAN.md), and the reasoning behind every design choice is in [docs/decisions/](docs/decisions).

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

| Concern | Design | Details |
| --- | --- | --- |
| Reads vs writes | `AbstractRoutingDataSource` sends `@Transactional(readOnly = true)` to the replica and everything else to the primary, behind a `LazyConnectionDataSourceProxy` | [02](docs/decisions/02-schema-and-data-access.md) |
| Redirect cache | Cache-aside. One Redis hash per code (1h TTL ± 10% jitter), negative caching, delete tombstones, falls back to Postgres if Redis fails | [04](docs/decisions/04-redis-cache-aside.md) |
| Click counting | Redis holds *deltas*, Postgres holds the truth. Every 30s the pending hash is swapped out atomically (`RENAME`) and applied in one `UPDATE`, idempotently | [05](docs/decisions/05-click-counting.md) |
| Short codes | 10 random base62 characters from `SecureRandom`, retried on unique-constraint collision | [03](docs/decisions/03-core-endpoints.md) |
| Schema | Flyway migrations. V1 is an exact port of the original schema (verified with `pg_dump` diff) | [02](docs/decisions/02-schema-and-data-access.md) |

### Redis keys

| Key | Type | TTL | Purpose |
| --- | --- | --- | --- |
| `code:{X}` | hash `url`, `created_at`, `clicks` | 1h ± 10% | Redirect lookup and stats |
| `miss:{X}` | string | 60s | "Code doesn't exist" / deleted-code tombstone |
| `rev:<sha256(url)>` | string → code | 24h | Returns the existing code when the same URL is shortened again |
| `{clicks}:pending` | hash code → delta | none | Clicks not yet written to Postgres |
| `{clicks}:flushing:<id>` | hash | none | A batch being written by the flush job |

### What can be lost, and when

| Failure | Clicks lost |
| --- | --- |
| App instance crash | At most the click of the request in flight |
| Crash during a flush | None (the batch is re-applied, exactly once) |
| Redis crash (AOF `everysec`) | About 1 second |
| Redis node lost without persistence or replica | Up to one flush interval (30s) |
| Redis unreachable | Clicks during the outage (redirects keep working; counted in `urlshortener.clicks.dropped`) |

`/stats` click counts are eventually consistent. The total in Postgres is exact once flushes complete.

---

## API

| Method | Path | Success | Errors |
| --- | --- | --- | --- |
| `POST` | `/shorten` | **201** `{"short_url", "created_at", "original_url"}` | **422** Pydantic-style `{"detail": [...]}`; **500** after 10 code collisions |
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
docker compose up --build        # app on http://localhost:8000
APP_PORT=18000 docker compose up --build   # if port 8000 is taken
docker compose down -v           # stop and delete the data volumes
```

Compose starts `app`, `db` (Postgres 15) and `redis` (Redis 7 with AOF and `volatile-lru`, see [05](docs/decisions/05-click-counting.md)). Only the app's port is published, so it doesn't clash with a Postgres or Redis already running on your machine.

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

The integration tests start real Postgres and Redis containers:
- Routing tests use **two separate Postgres containers**, so a read that wrongly goes to the primary fails.
- `RedisDownIT` points the app at a closed port to prove every endpoint falls back to Postgres.
- `ErrorContractIT` compares error bodies byte-for-byte with responses recorded from the Python service ([golden file](src/test/resources/golden/python-responses.txt)).

**Colima users:** Testcontainers needs two environment variables:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

CI ([`java-ci.yml`](.github/workflows/java-ci.yml)) runs `./mvnw verify`. It then builds the Compose stack, smoke-tests shorten → redirect → stats → delete, and runs the unchanged `locustfile.py` for 15 seconds as a functional check.

---

## Load testing

**Throughput and latency: to be re-measured.** The Python service had published Locust results, but they don't apply to this implementation. They are kept, with caveats, in [docs/legacy-python](docs/legacy-python). No numbers are published here until they have been measured in a way someone else can reproduce.

`locustfile.py` is unchanged from the Python version. Traffic mix: 70% redirects, 20% shortens with unique URLs, 10% stats.

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

## Behaviour changes from the Python service

The external API is unchanged. These internal bugs were fixed, each with a regression test:

| # | Python behaviour | Now |
| --- | --- | --- |
| B1 | `/stats` showed `clicks: 0` whenever the Redis counter had expired | DB clicks + pending delta |
| B2 | A reset Redis counter overwrote the DB total | Redis holds deltas; the DB is only ever added to |
| B3 | A click racing a delete leaked a key with no TTL | Pending clicks live in one hash, cleared on delete |
| B4 | `GET /clicks:<code>` redirected to the click count (key collision) | Namespaced keys and code validation → 404 |
| B5 | A 1500-char URL without a scheme caused a plain-text 500 | 422 validation error |
| B6 | Redirect and delete returned 500 when Redis was down | Falls back to Postgres |
| B7 | Any unexpected error stopped click syncing until restart | Supervised `@Scheduled` job |
| B8 | `KEYS clicks:*` blocked Redis on every sync | Atomic `RENAME` swap, no keyspace scan |
| B9 | Codes came from a predictable PRNG | `SecureRandom` |

Small framework-level differences (the `Allow` header on 405, `HEAD` support, `/docs` redirecting to the Swagger UI page) are listed in [docs/decisions/03](docs/decisions/03-core-endpoints.md).

---

## Project layout

```
src/main/java/com/ishitv/urlshortener/
  shorturl/        entity, repository, service, code generator
  shorturl/api/    controller, request/response records, Location encoding
  cache/           UrlCache: every Redis cache key, TTL and Lua script
  clicks/          ClickCounter, ClickFlushJob, ClickFlushScheduler
  config/          data sources + routing, typed app properties
  error/           FastAPI-compatible error responses
  web/             servlet filters (trailing-slash redirect, body caching)
src/main/resources/db/migration/   Flyway SQL
docs/decisions/    one document per migration phase: what, why, alternatives, failure modes, interview questions
```

## License

MIT
