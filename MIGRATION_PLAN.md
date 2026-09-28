# Migration Plan: FastAPI → Spring Boot

Status: **Complete (2026-09-28).** All seven phases are done, and the Python implementation was removed after commit `adeb867`. The owner delegated all decisions. Final choices are in §10, and they override anything earlier in this document that conflicts. Per-phase write-ups are in `docs/decisions/`.

Target: Java 21, Maven (wrapper committed), Spring Boot **4.1.1** (the default stable release on start.spring.io as of 2026-09-28).

---

## 1. What's in the repo

| Area | Files | Notes |
| --- | --- | --- |
| App | `app/main.py`, `app/routes/urls.py`, `app/routes/health.py`, `app/models.py`, `app/schemas.py`, `app/cache.py`, `app/db.py`, `app/config.py`, `app/sync_jobs.py` | About 400 lines of Python |
| Tests | `tests/conftest.py`, `tests/test_api_endpoints.py`, `tests/test_cache_integration.py`, `tests/test_read_replica.py` | SQLite + fakeredis (see §7) |
| Container | `Dockerfile` (single stage, python:3.11-slim), `docker-compose.yml` (redis:7, postgres:15, app) | |
| CI | `.github/workflows/run-ci.yml`: Lint → Check requirements → Test → Docker smoke | |
| Load test | `locustfile.py`, `Locust Results/**` (PNG screenshots only) | |
| Docs | `README.md`, `docs/AWS_ARCHITECTURE.md`, `docs/ArchitectureImage.png` | |
| IaC | **None.** There is no Terraform, CloudFormation, CDK or any other AWS provisioning code. | |
| Misc | `.env.example`, `.flake8`, `requirements.in/.txt`, `.vscode/settings.json`, `LICENSE.md` | |

---

## 2. Inventory

### 2.1 Endpoints

Every error body is FastAPI's `{"detail": ...}` shape. Routes are registered in this order, so `/health`, `/docs` and `/openapi.json` take precedence over `/{short_code}`.

#### `GET /health`
| Status | Body |
| --- | --- |
| 200 | `{"status": "ok"}` |

This is a static response and does not check the DB or Redis. The ALB health check uses this path.

#### `POST /shorten` (primary DB)
Request: `{"original_url": string}`. The string must be 10 to 1500 characters, and the length is checked **before** the scheme prefix is added. Unknown fields are ignored. A non-string value is rejected, because Pydantic's lax mode does not coerce int to str.

| Status | When | Body |
| --- | --- | --- |
| 201 | New code created, **or** existing code returned from the reverse-lookup cache | `{"short_url": "<BASE_URL>/<code>", "created_at": "<ISO-8601, no offset>", "original_url": "<normalised url>"}` |
| 422 | Missing, empty, too short or long, wrong type, or malformed JSON | `{"detail": [{"type": "...", "loc": ["body","original_url"], "msg": "...", "input": ..., "ctx": {...}}]}` |
| 500 | 10 consecutive unique-constraint collisions | `{"detail": "Failed to generate unique short code after 10 attempts. Please try again."}` |
| 500 | Unhandled error (Redis down, DB down, URL over 1500 chars after prefixing) | plain text `Internal Server Error` |

Behaviour:
1. If `original_url` doesn't start with `http://` or `https://`, prefix it with `https://`. No other URL validation is done.
2. Look up Redis key `{original_url}`, which maps to a short code.
   - If it hits and `created_at:{code}` is also cached, return from cache with **no DB access**.
   - If it hits but `created_at` is missing, read the row from the **primary**, cache `created_at:{code}`, and return. If the row is gone, fall through to step 3.
3. Try up to 10 times: generate a random 10-char code from `[A-Za-z0-9]`, INSERT, and commit. On `IntegrityError`, roll back and retry.
4. On success, set these keys: `{code}`→url (TTL 3600s), `{original_url}`→code (TTL 86400s), `clicks:{code}`=0 (TTL 3600s), `created_at:{code}` (TTL 3600s).

#### `GET /{short_code}` (read replica)
| Status | When | Headers / Body |
| --- | --- | --- |
| 302 | Found in cache or on the replica | `Location: <original_url>` (Starlette percent-encodes unsafe chars), empty body |
| 404 | Not in cache and not on the replica | `{"detail": "URL not found"}` |
| 500 | Redis unavailable (the error is not caught) | plain text |

Behaviour:
- **Cache hit** on `{code}`: return 302, then run the background task `INCRBY clicks:{code} 1`, which executes **after** the response is sent.
- **Miss**: query the replica. On success, cache `{code}`, `clicks:{code}` = DB clicks, and `created_at:{code}`, all with TTL 3600. Then return 302 and increment in the background.
- A code that exists on the primary but hasn't replicated yet returns **404**. A test asserts this.

#### `GET /stats/{short_code}` (read replica)
| Status | Body |
| --- | --- |
| 200 | `{"clicks": int, "created_at": "<ISO>", "original_url": "<url>"}` |
| 404 | `{"detail": "URL not found"}` |

Behaviour: read `clicks:{code}`, `{code}` and `created_at:{code}` from Redis. Redis errors are caught here, unlike in redirect. If all three are present, return from cache. Otherwise query the replica, which returns 404 if the row is missing. `created_at` always comes from the DB row on this path.

#### `DELETE /{short_code}` (primary DB)
| Status | Body |
| --- | --- |
| 204 | empty |
| 404 | `{"detail": "URL not found"}` |

Behaviour: delete cache keys `{code}`, `created_at:{code}` and `clicks:{code}` **before** the DB lookup. Then load the row from the primary, delete `{original_url}` using the DB value (a regression test covers this), delete the row, and commit. Redis errors are not caught.

#### Framework-provided routes
| Path | Behaviour | Needed? |
| --- | --- | --- |
| `GET /docs` | Swagger UI, 200 | A test asserts 200 |
| `GET /openapi.json`, `GET /redoc` | Spec and ReDoc | Not tested |
| Unknown path | 404 `{"detail": "Not Found"}` | |
| Wrong method | 405 `{"detail": "Method Not Allowed"}` | |
| Trailing slash, e.g. `/stats/x/` | 307 redirect to the path without the slash | |
| `HEAD /{code}` | 405 (FastAPI doesn't auto-add HEAD) | Spring will auto-answer HEAD. I plan to accept this difference. |

### 2.2 Data model

This comes from `app/models.py`, which is created by `Base.metadata.create_all()` at import time. There are no migrations.

Table **`codes`** (the README calls it `urls` in one place, which is wrong):

| Column | SQLAlchemy | PostgreSQL DDL produced | Notes |
| --- | --- | --- | --- |
| `id` | `Integer, primary_key` | `SERIAL NOT NULL`, PK `codes_pkey` | |
| `clicks` | `Integer, default=0, nullable=False` | `INTEGER NOT NULL` | The default is **Python-side only**. There is no `DEFAULT 0` in the DDL. |
| `short_code_chars` | `String, nullable=False, unique=True` | `VARCHAR NOT NULL`, `UNIQUE` constraint `codes_short_code_chars_key` | Unbounded varchar |
| `original_url` | `String(1500), nullable=False` | `VARCHAR(1500) NOT NULL` | Not indexed |
| `created_at` | `DateTime, default=now(utc)` | `TIMESTAMP WITHOUT TIME ZONE NOT NULL` | Python-side default. The value is stored as UTC wall-clock time and the offset is dropped. |

**Indexes:** `codes_pkey` (btree on `id`) and `codes_short_code_chars_key` (unique btree backing the constraint). There are no others. In Phase 2 I'll confirm the exact DDL by running the Python `create_all` against Postgres 15 in Docker and diffing its `pg_dump --schema-only` output against the Flyway-created schema.

### 2.3 Redis keyspace (db 0, string values)

| Key | Value | TTL | Written by | Deleted by |
| --- | --- | --- | --- | --- |
| `{code}` | original URL | 3600s | shorten, redirect miss | delete |
| `{original_url}` | code (reverse lookup for de-dup) | 86400s | shorten | delete |
| `clicks:{code}` | integer | 3600s when SET. `INCRBY` keeps an existing TTL but creates **no TTL** if the key is missing. | shorten, redirect miss, redirect (INCRBY) | delete |
| `created_at:{code}` | ISO timestamp | 3600s | shorten, shorten rehydrate, redirect miss | delete |

### 2.4 Background job: `sync_to_db` (app/sync_jobs.py)
- A daemon thread started in the FastAPI lifespan. It runs once immediately, then sleeps 30s after each run, which is **fixed-delay** timing.
- Uses its own pool, `sync_engine` (primary, size 2).
- Algorithm: `KEYS clicks:*`, then one `GET` per key (N round trips), then `SELECT … WHERE short_code_chars IN (…)` on the primary, then set `row.clicks = <redis value>` (an **absolute overwrite, not a delta**), then commit. SQLAlchemy emits one `UPDATE` per changed row via executemany. It is not a single statement.
- Error handling: `RedisError` is printed. `IntegrityError` causes a rollback. Other `SQLAlchemyError`s are printed. **Any other exception kills the thread silently**, and clicks then stop syncing until restart.
- Every app instance runs its own copy. With 2 EC2 instances, both write the same absolute values.

### 2.5 Configuration

| Name | Source | Default | Used for |
| --- | --- | --- | --- |
| `DATABASE_URL` | env / `.env` | `sqlite:///./test.db` | Primary (SQLAlchemy URL format) |
| `READ_REPLICA_URL` | env | `sqlite:///./test_replica.db` | Replica |
| `BASE_URL` | env | `http://localhost:8000` | `short_url` prefix |
| `REDIS_HOST` / `REDIS_PORT` | env | `localhost` / `6379` | Redis, db 0, no auth/TLS |
| `DB_PASS`, `RDS_ENDPOINT` | `.env.example` only | | **Unused** by the code |
| Hard-coded pools | `main.py` | primary 25+5, replica 25+5, sync 2+0 | |
| Hard-coded pool options | `db.py` | `pool_timeout=5`, `pool_recycle=3600`, `pool_pre_ping=True` | |
| Hard-coded app constants | `urls.py`, `cache.py`, `sync_jobs.py` | code length 10, alphabet base62, 10 retries, URL length 10–1500, TTLs 3600/86400, flush every 30s | |
| Port | Dockerfile/uvicorn | 8000 | Locust default host |

---

## 3. Component mapping

| Python | Spring equivalent | Rationale |
| --- | --- | --- |
| `app/main.py` (wiring, lifespan) | `UrlShortenerApplication` + `@Configuration` classes | Explicit bean definitions instead of module globals |
| `app/config.py` | `AppProperties` record with `@ConfigurationProperties("app")` + `application.yml` mapping `${BASE_URL}` etc. | Typed, validated at startup |
| `app/db.py` `make_engine` ×3 | `DataSourceConfig`: 3 explicit `HikariDataSource`s (primary, replica, flush), `ReplicaRoutingDataSource extends AbstractRoutingDataSource` over primary and replica, wrapped in `LazyConnectionDataSourceProxy` | See §4 |
| SQLAlchemy `Code` model | `@Entity @Table(name="codes") ShortCode` using Java field names mapped to the existing columns, `@GeneratedValue(IDENTITY)` | The entity is never serialized to JSON |
| `Base.metadata.create_all` | Flyway `V1__create_codes_table.sql` + `spring.jpa.hibernate.ddl-auto=validate` | Validation fails fast if the entity and schema drift |
| Pydantic `URLRequest`/`URLResponse`/`StatsResponse` | `record ShortenRequest`, `record ShortUrlResponse`, `record StatsResponse` with `@JsonProperty("original_url")` etc. + Bean Validation `@NotNull @Size(min=10,max=1500)` | Explicit snake_case per field instead of a global naming strategy |
| `session.query(Code).filter_by(short_code_chars=…)` | `ShortCodeRepository extends JpaRepository<ShortCode,Integer>` with `Optional<ShortCode> findByShortCode(String)` | Derived query. Nothing else needs a custom query. |
| `routes/urls.py` (logic + HTTP mixed) | `ShortUrlController` (HTTP only) → `ShortUrlService` (logic) → repository + `UrlCache` | Layering as required |
| `random.choices(base62, k=10)` | `ShortCodeGenerator` using `SecureRandom` over the same alphabet | Same format. See the note in §6. |
| `app/cache.py` `RedisCache` | `UrlCache` over `StringRedisTemplate` (Lettuce) with explicit, documented key and TTL methods | Not `@Cacheable`. See §5. |
| FastAPI `BackgroundTasks` click increment | `ClickCounter.increment()`, a synchronous `INCR` inside try/catch before returning the 302 | See §5 |
| `sync_jobs.py` thread | `ClickFlushJob` with `@Scheduled(fixedDelayString="${app.clicks.flush-interval}")`, `SCAN`+`MGET`, and one bulk `UPDATE … FROM unnest(?,?)` via `JdbcTemplate` on the flush pool | A real single statement, where Python used N UPDATEs |
| `HTTPException(404/500)` | `ShortCodeNotFoundException`, `CodeGenerationException` + `@RestControllerAdvice` producing `{"detail": "..."}`. It also maps validation errors to 422 in FastAPI shape, and 404/405 to `{"detail":"Not Found"}` / `{"detail":"Method Not Allowed"}`. | Preserves the contract |
| `routes/health.py` | `HealthController` returning `Map.of("status","ok")` | Actuator's shape differs. Actuator may be added separately under `/actuator` for metrics. |
| `/docs` | springdoc-openapi with `springdoc.swagger-ui.path=/docs`, `springdoc.api-docs.path=/openapi.json` | Keeps the tested `/docs` route. ReDoc is dropped. |
| pytest + SQLite + fakeredis | JUnit + Testcontainers (Postgres 15 ×2, Redis 7), MockMvc/RestClient | Real engines. See §7. |
| flake8, pip-compile | `maven-compiler-plugin` with `-Xlint:all`; Maven Enforcer (Java 21, dependency convergence) | Lightweight equivalent. No extra formatter unless you want one. |
| Dockerfile | Multi-stage: `eclipse-temurin:21-jdk` + `./mvnw package` → `eclipse-temurin:21-jre`, non-root user, `EXPOSE 8000` | |
| docker-compose | Same 3 services and healthchecks. The app gets JDBC/Redis env vars and an app healthcheck on `/health`. | |
| GitHub Actions | `setup-java@v4` (temurin 21, Maven cache) → `./mvnw -B verify` → compose smoke job (kept from the current workflow) | Testcontainers runs natively on `ubuntu-latest` |
| `locustfile.py` | **Unchanged**. The Java app listens on port 8000 and paths and JSON are identical. | |

---

## 4. Read/write split (it exists, so it gets ported)

- **Routing:** `ReplicaRoutingDataSource.determineCurrentLookupKey()` returns `REPLICA` when `TransactionSynchronizationManager.isCurrentTransactionReadOnly()` is true, and `PRIMARY` otherwise. Redirect and stats service methods are `@Transactional(readOnly = true)`. Shorten and delete use read-write transactions.
- **Why `LazyConnectionDataSourceProxy`?** `JpaTransactionManager` grabs a connection when the transaction *begins*, before the read-only flag is visible to the router. Without the proxy, every query would go to the primary. The proxy delays fetching the physical connection until the first statement runs. I'll add a code comment for this, and a Testcontainers test that uses two separate Postgres containers with no replication and asserts reads go to the replica. That mirrors `test_read_replica.py`.
- **Pools (HikariCP, set explicitly in `application.yml`, per instance):**

  | Pool | `maximum-pool-size` | `minimum-idle` | From |
  | --- | --- | --- | --- |
  | primary | 30 | 25 | SQLAlchemy 25 + overflow 5 |
  | replica | 30 | 25 | 25 + 5 |
  | flush | 2 | 1 | 2 + 0 |

  Common settings: `connection-timeout: 5000` (was `pool_timeout=5`) and `max-lifetime: 3600000` (was `pool_recycle=3600`). Hikari validates connections on borrow, which covers `pool_pre_ping`. The connection math is unchanged: 2 instances × (30+2) = 64 on the primary and 2 × 30 = 60 on the replica, against the documented ~87 RDS limit. That RDS limit is itself unverified; see §8.
- **Why a third pool?** It's still two routed DataSources as you asked. The flush pool sits outside the router because the job only needs `JdbcTemplate`, and isolating it preserves the existing guarantee that the flush can't starve request traffic.

---

## 5. Cache and click-counter behaviour in Spring

> Superseded in part by **§10.3**, which is the final Redis design. This section describes a straight port and is kept for comparison.

- **Cache-aside:** implemented by hand in `ShortUrlService` via `UrlCache`. Spring's `@Cacheable` was rejected for three reasons. It can't express the multi-key writes (forward, reverse, clicks, created_at) or the per-key TTLs. It hides the ordering of cache and DB operations, which is exactly what you'll be asked about. It also carries the proxy self-invocation pitfall.
- **Key schema:** keep the Python keys byte-for-byte by default (Decision D3), so a mixed Python/Java fleet or a rolling cutover can share one Redis.
- **Delete ordering:** Python invalidates the cache *before* the DB delete commits. That leaves a window where a concurrent redirect re-populates the cache from the replica. Java will commit the DB delete first and then invalidate. The service method will not be `@Transactional`: the repository call commits, then the cache is cleared. This is documented as a small, deliberate internal change that doesn't touch the contract. Replica lag can still re-populate a deleted code for up to 1 hour of TTL. That will be documented, along with how to detect it.
- **Click increment:** a synchronous `INCR` before returning 302, wrapped so that a Redis failure never fails the redirect. The alternative was `@Async`, which matches FastAPI's after-response semantics. I rejected it because it adds an executor and a queue that can drop increments, and INCR is sub-millisecond anyway.
- **Flush job and its data-loss window:** the Python README says clicks are lost "if the app crashes between syncs". **That is inaccurate.** Counts live in Redis, so an app crash only loses increments still in flight. The real loss cases are Redis restarting without persistence, failover, or eviction, which lose up to 30s of clicks. Eviction of `clicks:{code}` is worse (see bug B2). This will be written up properly in `docs/decisions/05-*.md`.
- **Multiple instances:** `@Scheduled` runs on every instance, as it does today. The writes are idempotent absolute values, so this is safe but duplicated work. ShedLock was considered and deferred.

---

## 6. Behaviour notes and existing bugs

These are behaviours of the current code that you should know about. The **default plan is to preserve anything externally visible**, and to fix the rest only where you approve (Decision D2). Each fix would get its own commit, test and decision-doc entry.

| # | Issue | Where | Externally visible? | Proposed |
| --- | --- | --- | --- | --- |
| B1 | `/stats` returns **`clicks: 0`** whenever `clicks:{code}` is absent from Redis, even if the DB has a real count. `get_int` returns 0, never `None`, so the DB fallback for clicks never runs. | `urls.py:62,92` | Yes (wrong data) | Fix: fall back to DB clicks when the key is missing |
| B2 | If `clicks:{code}` expires or is evicted while `{code}` is still cached, the next `INCRBY` creates it at `1` with **no TTL**. The flush job then **overwrites** the DB count with 1. | `cache.py:21`, `sync_jobs.py:43` | Yes (data loss) | Fix in Phase 5. Options: seed from DB on miss, or switch to delta flush (`GETDEL`, `clicks = clicks + Δ`). Needs a decision. |
| B3 | A click racing with DELETE recreates `clicks:{code}` without a TTL. The key leaks forever and is scanned every 30s. | background task after delete | No | Fix alongside B2 |
| B4 | The key namespace is shared. `GET /clicks:AbCdEf1234` hits Redis key `clicks:…` and **302-redirects to the click count**. The same applies to `created_at:` keys and to any original URL used as a path. | `urls.py:33` | Yes (edge case) | Fix: reject non-`[A-Za-z0-9]` codes with 404 before touching Redis |
| B5 | A 1500-char input without a scheme becomes 1508 chars after prefixing. Postgres rejects it and the client gets a plain-text **500**. | `urls.py:131` | Yes | Fix: validate the post-normalisation length and return 422 |
| B6 | Redirect and delete return 500 when Redis is down, although the DB could serve them. Stats already degrades gracefully. | `urls.py:33,106` | Yes (availability) | Fix: fall back to the DB on Redis errors |
| B7 | The flush thread dies permanently on any unexpected exception. | `sync_jobs.py:14` | No | Structurally fixed: `@Scheduled` catches, logs and runs again |
| B8 | `KEYS clicks:*` is O(N) and blocks Redis. It is followed by N separate GETs. | `sync_jobs.py:23-30` | No | Use `SCAN` + `MGET` |
| B9 | Random codes use `random`, which is not a CSPRNG. | `urls.py:165` | No | Use `SecureRandom` |

**"Base62 encoding":** the Python code does **not** encode a numeric ID into base62. It draws 10 random characters from the base62 alphabet (62^10 ≈ 8.4×10^17 codes). I'll keep random generation because sequential encoding makes codes enumerable, and write the unit tests you asked for against `ShortCodeGenerator`: length, alphabet, and no collisions over a large sample. If you'd rather have a real `Base62.encode(long)` for an interview talking point, say so. It would be a change of design, not a port.

**`created_at` format fidelity:** Postgres returns a naive timestamp, and Pydantic serializes it with no offset. Before writing Phase 3, I'll bring up the Python service with Compose and **record real responses as golden fixtures**. Java will use `LocalDateTime` truncated to microseconds, with the Jackson format pinned to match the fixtures exactly, including fractional digits. Jackson's default formatting differs from Python's. The same fixtures will cover the 422 bodies.

**Jackson coercion:** Jackson coerces `{"original_url": 123}` to `"123"` by default, while Pydantic rejects it with 422. I'll disable scalar coercion for that field to keep parity.

**`Location` header:** Starlette percent-encodes unsafe characters. Spring's `URI.create` would throw on the same input. I'll port the same safe-character set explicitly and test it.

---

## 7. Tests: what exists and how it maps

**Important finding:** the CI "Test" job starts Postgres and Redis service containers, but `tests/conftest.py` forces SQLite and every test uses fakeredis. **CI never exercises Postgres or Redis.** The README's description of that job is misleading.

| Python test | Java plan |
| --- | --- |
| `TestPhase1RepositorySetup` (`.env` exists, `DATABASE_URL` set) | Dropped. It checks the dev environment, not the app. The Spring equivalent is the context-loads test, which fails if config is missing. |
| `TestPhase2CoreEndpoints` (201/422/302/404/204, click increment) | `ShortUrlApiIT` (Testcontainers + MockMvc), one-to-one |
| `TestPhase3UnitTests` (uniqueness, alphabet, retry) | `ShortCodeGeneratorTest` (pure unit) + collision-retry test with a stubbed generator that forces the unique-constraint path |
| `TestPhase4Deployment` (`/health`, `/docs`) | `HealthIT`, `DocsIT` |
| `TestPhase5LoadTesting` (50 concurrent creates, 100 redirects → clicks=100) | `ConcurrencyIT` |
| `TestShortenCaching` (dedup, forward/clicks/created_at keys, reverse TTL, delete clears reverse key even if forward expired) | `ShortenCachingIT`, asserting directly against the real Redis container |
| `test_cache_integration.py` | Mostly tests Redis itself. I'll port what exercises our wrapper (TTLs, get/set/delete, counters) as `UrlCacheIT`. |
| `test_read_replica.py` routing tests | `ReadWriteRoutingIT` with **two** Postgres containers: writes land on the primary, reads (redirect/stats) hit the replica, and unreplicated rows give 404 |
| `test_read_replica.py` "concept" tests | Dropped. They test SQLAlchemy and SQLite, not the app. |
| (new) | `ClickFlushJobIT` (scheduler disabled in tests via property and invoked directly), `FlywaySchemaIT` (asserts columns and indexes), golden-fixture contract tests |

---

## 8. README and docs claims not backed by code in the repo

**Performance figures (all of them).** These include the 100/500/1000/2000-user tables, the ~687/734/715 RPS figures, the P50/P95 values, the "~715 RPS ceiling", the Section 1–3 comparisons, and the "8x faster" and "3.4x higher" claims. Their only evidence is the PNG screenshots in `Locust Results/`. I spot-checked two and the numbers match the screenshots. But there are no raw CSVs, no recorded Locust parameters beyond prose, no commit hash tied to each run, and no IaC to rebuild the environment. None of it is reproducible from the repo, and none of it describes the Java service. **These will be replaced with "to be re-measured"** plus Locust instructions.

**Internal contradictions:**
- `docs/ArchitectureImage.png` shows "500 users: 0.31% fail, 403 RPS" and "750 users: 1.43% fail, 252 RPS". The README says 500 users gave 0.010% and ~687 RPS. The 750-user run appears nowhere else.
- The README's 1000-user section says the gain came from `/shorten` cache hits "under the fixed-URL locustfile". The same README says the 1000-user test used unique `uuid4()` URLs, which can never hit that cache.
- The README says `docker compose up` "starts two containers" and that Redis and Postgres run on the host. The compose file actually defines three services, including Redis and Postgres.
- `AWS_ARCHITECTURE.md` says "Python 3.13", but the Dockerfile uses 3.11.
- The README refers to a `urls` table. The actual table is `codes`.
- The AWS doc's "Shorten" request flow omits the reverse-lookup cache.

**Infrastructure:** EC2 t3.small ×2, RDS db.t4g.micro primary + replica, ElastiCache Valkey, ALB, VPC and subnets, `eu-west-2`, and "~87 max connections". **None of this is defined in code.** It exists only in prose and a diagram.

**Unsupported causal explanations:**
- The p95 spikes every 30s are attributed to the flush UPDATE "holding row locks" that block `/shorten` INSERTs. In PostgreSQL, row locks from an UPDATE don't block INSERTs of *different* rows. Nothing in the repo (pg_locks captures, traces) supports this explanation, so it's a hypothesis, not a finding.
- The claim that the flush "commits everything in one UPDATE / one round-trip" is inaccurate, because SQLAlchemy emits one UPDATE per dirty row.

**CI claim:** "Spins up Postgres and Redis service containers, runs the full test suite." The containers do start, but the tests never use them (§7).

---

## 9. Phase plan (commits on `feat/spring-migration`)

Python stays in place until Phase 7. Maven lives at the repo root (`pom.xml`, `src/`) alongside `app/` until then. After every phase: `./mvnw verify`, a diff summary, `docs/decisions/NN-<topic>.md`, then **stop for review**.

| Phase | Commits (Conventional) | Contents |
| --- | --- | --- |
| 1 | `build: add spring boot maven skeleton`, `feat: add health endpoint`, `ci: run mvn verify on push` | pom, wrapper, app class, `/health`, context test, CI job added next to the Python jobs |
| 2 | `feat(db): add flyway schema for codes table`, `feat(db): add ShortCode entity and repository`, `feat(db): add primary/replica routing datasource` | V1 migration, `ddl-auto=validate`, 3 Hikari pools, `FlywaySchemaIT`, `ReadWriteRoutingIT` (repository level). Flyway uses `baseline-on-migrate` so existing prod DBs are adopted at V1 without re-running DDL. |
| 3 | `feat: add short code generator`, `feat(api): add shorten/redirect/stats/delete endpoints`, `feat(api): add FastAPI-compatible error handling` | Golden fixtures captured from Python first. Controllers, services, DTOs, validation, 404/405/422/500 bodies, springdoc at `/docs`. |
| 4 | `feat(cache): add redis cache-aside for redirects` | `UrlCache`, forward/reverse/created_at keys, TTLs, delete-then-invalidate, B4/B6 fixes if approved |
| 5 | `feat(clicks): add redis click counters`, `feat(clicks): add scheduled bulk flush` | INCR, `ClickFlushJob`, B1/B2/B3 fixes per decision, data-loss window doc |
| 6 | `test: …`, `build(docker): multi-stage dockerfile and compose`, `docs: rewrite readme for spring service` | Remaining IT coverage, Docker, compose smoke test, Locust run against Compose (functional check only, with no numbers published), README rewrite |
| 7 | `chore: remove python implementation` | Delete `app/`, `tests/`, `requirements*`, `.flake8`, `.vscode/`, Python CI jobs. Handle `Locust Results/` and `docs/AWS_ARCHITECTURE.md` per D6. |

---

## 10. Final decisions

The owner delegated every decision, with one guiding goal: fix the bugs, and pick designs that teach the most about Redis and system design while staying explainable.

### 10.1 Summary

| # | Decision | Choice | Why |
| --- | --- | --- | --- |
| D1 | Toolchain | JDK 21 (Homebrew `openjdk@21`) + committed Maven wrapper | Installed 2026-09-28 |
| D2 | Bugs B1–B9 | **All fixed.** Each fix gets its own commit, a regression test, and an entry in the phase's decision doc. | Owner's instruction |
| D3 | Redis keys | **Namespaced and restructured** (§10.3). This reverses the earlier "identical keys" recommendation. | The new click model is incompatible with Python's anyway, so a mixed fleet was never safe. That removes the only reason to keep the old keys. |
| D4 | Env vars | JDBC style: `DB_PRIMARY_URL`, `DB_REPLICA_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `BASE_URL`, `SERVER_PORT` (default 8000) | No hidden URL parsing |
| D5 | 422 bodies | Byte-compatible with Pydantic for the reachable cases, verified against golden fixtures recorded from the Python service | "Exact contract" |
| D6 | Legacy results and AWS docs | Move to `docs/legacy-python/` with a header saying they describe the old implementation | Keeps history honest |
| D7 | Boot 4.1.1 managed versions | Use the BOM's versions (Jackson 3, the JUnit Jupiter it manages, Testcontainers 2.x) and record them in `docs/decisions/01-*.md` | Don't fight the BOM |
| D8 | Observability | Spring Boot Actuator + Micrometer (`/actuator/health`, `/actuator/prometheus`) with custom counters: cache hit/miss, negative-cache hit, clicks dropped, flush batch size/duration, pending backlog | Each decision doc's "how would you detect it" needs a real signal to point at |
| D9 | Threading | Platform threads (Tomcat default). Virtual threads are discussed in the decision doc but not enabled. | The Hikari pool is the real concurrency limit. Changing two variables at once hides which one mattered. |

### 10.2 Bug fixes: what each teaches

| Bug | Fix | Concept |
| --- | --- | --- |
| B1 stats shows 0 clicks | Stats = cached total, or DB clicks + pending delta | Cache fallback semantics |
| B2 counter reset overwrites DB | **Delta counting**: Redis holds only un-flushed increments, and the DB is the source of truth | Source of truth; idempotent vs non-idempotent writes |
| B3 key leak after delete | Delete removes the code's pending delta. Pending lives in one hash, so there are no per-code orphan keys. | Key lifecycle |
| B4 namespace collision | Prefixed keys + path validation `^[A-Za-z0-9]{1,32}$` → 404 | Keyspace design |
| B5 1508-char URL → 500 | Validate the normalised length → 422 | Validate what you store |
| B6 Redis down → 500 | Every Redis call degrades to the DB. Clicks are dropped and counted in a metric. | Graceful degradation; a cache is an optimisation, not a dependency |
| B7 flush thread dies | `@Scheduled` + catch/log/metric per run | Supervision |
| B8 `KEYS` blocks Redis | Removed entirely by the hash-swap design (§10.3) | O(N) commands on a single-threaded server |
| B9 non-CSPRNG codes | `SecureRandom` | Guessability |

### 10.3 Final Redis design

| Key | Type | TTL | Purpose |
| --- | --- | --- | --- |
| `code:{code}` | HASH `{url, created_at, clicks}` | 3600s ± 10% jitter | Forward lookup + stats. **One key per code**, so eviction removes all fields together. That fixes the "created_at evicted, url kept" failure described in the README. |
| `rev:{sha256(url)}` | STRING → code | 86400s | Reverse de-dup. The URL is hashed so key size is bounded (URLs can be 1500 bytes). |
| `miss:{code}` | STRING | 60s | **Negative cache**, which stops repeated lookups of non-existent codes from reaching the DB (cache penetration). DELETE also writes it as a **tombstone**, so a lagging replica can't re-populate a deleted code. |
| `{clicks}:pending` | HASH code → delta | none | Un-flushed click increments |
| `{clicks}:flushing:<epochMs>-<uuid>` | HASH | none | A batch claimed by one flush run |

- **Redirect hit:** a Lua script against `code:{code}` returns the URL and runs `HINCRBY clicks 1` only if the hash exists. The check and the increment are atomic, so a key expiring between them can never leave a half-built hash. The redirect then does `HINCRBY {clicks}:pending code 1`.
- **Redirect miss:** check `miss:{code}` (on a hit, return 404) → replica → seed `code:{code}` with `clicks = db.clicks + pending` → count the click. If the row isn't found, write `miss:{code}` and return 404.
- **Flush every 30s (fixed delay):**
  1. `RENAME {clicks}:pending → {clicks}:flushing:<id>`. This is an atomic swap: new clicks go into a fresh pending hash while the old one is flushed. No `KEYS` and no `SCAN` over millions of keys.
  2. `HGETALL` the batch.
  3. In one DB transaction on the flush pool: `INSERT INTO click_flush_batches(batch_id) ON CONFLICT DO NOTHING`. If that inserted a row, run `UPDATE codes SET clicks = clicks + d.delta FROM unnest(?::text[], ?::int[]) AS d(code, delta) WHERE …`, which is one statement and one round trip.
  4. `DEL` the batch key.

  Leftover `flushing` keys older than 5 minutes come from crashed runs and are re-processed. The batch-ID table makes re-processing **idempotent**, so we get exactly-once application without a distributed lock. Any instance can run the job safely, which is why ShedLock isn't needed. Old batch IDs are pruned after 24h.
- **The `{clicks}` hash tag** keeps `pending` and `flushing:*` in one Redis Cluster slot, because `RENAME` across slots fails. The single hot `pending` key is a documented scaling limit, and sharding it is the follow-up.
- **Schema addition:** `V2__click_flush_batches.sql`. `V1` stays an exact port of the Python schema.
- **Redis server config** (compose; documented for ElastiCache): `appendonly yes`, `appendfsync everysec`, `maxmemory-policy volatile-lru`. Pending click hashes have no TTL, so `volatile-lru` **never evicts un-flushed clicks**, only cache entries. The click-loss window on a Redis crash is about 1s (AOF fsync). An app crash loses at most the in-flight request's click.
- **Consistency statement** (for the README and interviews): `/stats` click counts are **eventually consistent**. They are seeded from a possibly lagging replica plus the pending deltas, so they can briefly under-count. The DB total is exact once flushes complete.
- **Cutover from Python:** stop the Python fleet, let its final sync run, `FLUSHDB` the old keys, then start Java. The new keys don't overlap with the old ones anyway.

### 10.4 Phase changes

| Phase | Change |
| --- | --- |
| 1 | Add Actuator. |
| 4 | Implement `code:`, `rev:` and `miss:` with jitter and tombstones. |
| 5 | Implement delta counting, the hash swap, and idempotent flush. `V2__click_flush_batches.sql` ships here, together with the feature that needs it. |

Everything else in §9 is unchanged.
