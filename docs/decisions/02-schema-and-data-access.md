# 02: Schema (Flyway), entity, repository, read/write split

## What was built

- `V1__create_codes_table.sql`, an exact port of the table SQLAlchemy created. **How "exact" was verified:**
  1. Ran the Python `Base.metadata.create_all()` against a fresh Postgres 15 container.
  2. Applied V1 to a second fresh database.
  3. Diffed `pg_dump --schema-only` of the two: **identical**.

  `FlywaySchemaIT` keeps checking the columns, types, nullability, defaults and indexes on every build.
- The `ShortCode` entity (table `codes`) and `ShortCodeRepository`.
- Three HikariCP pools (primary, replica, flush). `ReplicaRoutingDataSource` (an `AbstractRoutingDataSource`) sits behind a `LazyConnectionDataSourceProxy`.
- `ReadWriteRoutingIT` uses **two unconnected Postgres containers** as primary and replica, so routing mistakes are visible: a row on the primary only is invisible to anything routed to the replica.

### Facts about the schema you should know

- `clicks` has **no DB default**. SQLAlchemy's `default=0` was applied in Python, so the entity sets `clicks = 0` in its constructor.
- `created_at` is `TIMESTAMP WITHOUT TIME ZONE` holding UTC wall-clock time. Java maps it to `LocalDateTime`. The app always generates it with `LocalDateTime.now(ZoneOffset.UTC)`, truncated to microseconds (Postgres's precision).
- The only indexes are `codes_pkey` and `codes_short_code_chars_key`, the unique index behind the unique constraint. That unique index also serves the lookup-by-code query, so no separate index is needed.

## Why this approach

**Flyway instead of `ddl-auto=update`.** Schema changes become reviewed, versioned SQL files that run the same way everywhere. Hibernate only *validates* (`ddl-auto: validate`), so if the entity and the table drift apart, the app refuses to start instead of corrupting data at runtime. `baseline-on-migrate` lets the service start against the existing Python-era database, which has the table but no Flyway history: it records V1 as already applied.

**How routing works, step by step:**

1. A repository method annotated `@Transactional(readOnly = true)` starts a transaction.
2. `JpaTransactionManager` asks the DataSource for a connection. It gets a **lazy proxy** connection, and no real connection is taken from any pool yet.
3. Spring publishes "this transaction is read-only" in `TransactionSynchronizationManager`.
4. Hibernate runs the first SQL statement. Only now does the proxy ask `ReplicaRoutingDataSource` for a real connection, and the router sees `readOnly == true` and returns a **replica** connection.

Without step 2's laziness, the router would be asked in step 2, before step 3, and would always choose the primary. **Nothing would fail; reads would just silently hit the primary.** That is why it gets a test and a code comment.

**Why the transactions are on repository methods, not the service.** Spring's default propagation is `REQUIRED`: an inner `@Transactional` *joins* an outer transaction and uses the outer one's read-only flag. If the service were `@Transactional`, `findByShortCode` would run inside a read-write transaction and go to the primary. Keeping transactions at the repository level makes each call's routing obvious from its own annotation. It also means no DB connection is held while the service talks to Redis.

**Three pools.** They mirror the three SQLAlchemy engines. Sizes and reasoning live in `application.yml`:
- The web pools get 30 connections each: SQLAlchemy's `pool_size 25` + `max_overflow 5`. Hikari has no overflow, just a hard maximum.
- The flush pool gets 2, isolated so the batch job and request traffic can't starve each other.
- Connection budget: 2 instances × (30 + 2) = **64 on the primary** and 2 × 30 = **60 on the replica**, below the documented ~87 limit of db.t4g.micro.

**The replica pool is read-only at the connection level** (`read-only: true`, `readOnlyMode=always`). A routing bug then fails loudly with `cannot execute INSERT in a read-only transaction` instead of writing to the wrong database. This needs `readOnlyMode=always` because the Postgres driver otherwise ignores read-only outside explicit transactions. On a real RDS replica, writes fail anyway, but in docker compose the "replica" is the same database, so this is what enforces the split there.

**`open-in-view: false`.** Spring Boot's default keeps a Hibernate session open for the whole HTTP request, holding a connection during Redis calls and JSON rendering. That wastes pool capacity, and pool capacity was the README's measured bottleneck.

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| `ddl-auto=update` / `create` | Non-reviewable, non-reversible schema changes decided by an ORM at startup. It can't express the exact constraint names. |
| Liquibase | Equivalent capability. Plain SQL Flyway files read like the `pg_dump` they were verified against. |
| `LazyConnectionDataSourceProxy.setReadOnlyDataSource(replica)` (new in Spring 6.1) | Does the same routing with less code. The brief asked for `AbstractRoutingDataSource`, which also makes the mechanism visible, and that is better for explaining it. This is the natural follow-up simplification. |
| Two separate `EntityManagerFactory`s (one per DB) | Two sets of repositories, and every caller has to pick the right one. Routing by transaction attribute keeps a single repository. |
| AWS JDBC wrapper / RDS Proxy / PgBouncer doing the split | Real options at scale. The README itself suggests RDS Proxy. They move the routing decision out of the app, though, and here the point is to show and test it. |
| `@Transactional` on the service layer | See above: it changes the routing of the inner calls and holds connections across Redis round trips. |

## What breaks under load or failure, and how to detect it

- **Pool exhaustion.** With 30 connections and slow queries, request 31 waits up to `connection-timeout` (5s) and then fails with `SQLTransientConnectionException`. Detect it with `hikaricp.connections.pending` > 0 sustained, `hikaricp.connections.usage` p99, and `hikaricp.connections.timeout` count, all tagged by `pool` (primary, replica, flush) in `/actuator/prometheus`.
- **Replication lag.** A link created on the primary may 404 on a redirect cache miss until the replica catches up. A test pins this behaviour; it is inherited from the Python design. Phase 4's cache write on shorten hides it for the common path. Detect it with the RDS `ReplicaLag` CloudWatch metric, and a spike in 404s on `GET /{code}` right after creations.
- **Primary failover.** RDS promotes the standby, DNS changes, and existing connections break. Hikari discards them on validation and re-connects. `max-lifetime` (1h) bounds how long a pool can keep connections to a stale IP. Detect it through bursts of `hikaricp.connections.creation` time and 5xx during the failover window.
- **Too many connections.** Scaling out to more instances multiplies the pool sizes. A third instance would put 96 connections on an ~87-connection primary, and new connections would fail with `FATAL: too many connections`. Detect it with the RDS `DatabaseConnections` metric vs `max_connections`. The fix is a pooler (RDS Proxy / PgBouncer) or smaller per-instance pools.
- **Mis-routed write.** It fails immediately because the replica pool is read-only. Detect it as a 5xx with a `read-only transaction` error in the logs.

## Interview questions

1. Walk through exactly when the routing key is evaluated in a `@Transactional(readOnly = true)` call. What goes wrong without `LazyConnectionDataSourceProxy`, and why would no test that only checks results catch it?
2. Why is `@Transactional` on the repository methods rather than the service, and what does `REQUIRED` propagation do to an inner read-only method?
3. How did you size the Hikari pools, and what changes if you add a third application instance?
4. The Python schema has no `DEFAULT 0` on `clicks` and stores UTC in a timestamp without time zone. Would you fix either, and how would you migrate a live table without downtime?
5. A user creates a link and immediately clicks it, but gets a 404. Explain the cause and three ways to fix it, with their trade-offs.
