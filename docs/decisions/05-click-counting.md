# 05: Click counting: Redis deltas and a scheduled, idempotent bulk flush

## What was built

A click takes this path:

```
GET /{code}
  ├─ Lua: HGET code:{X} url; if present HINCRBY code:{X} clicks 1   (display total, 1 round trip)
  └─ HINCRBY {clicks}:pending X 1                                    (delta to persist)

every 30s (fixed delay), on every instance — ClickFlushJob:
  1. RENAME {clicks}:pending → {clicks}:flushing:<epochMs>-<uuid>     atomic swap (Lua-guarded)
  2. HGETALL the batch
  3. one transaction on the flush pool:
       INSERT INTO click_flush_batches(batch_id) ON CONFLICT DO NOTHING
       if inserted: UPDATE codes SET clicks = clicks + delta FROM unnest(codes[], deltas[])   (one statement)
  4. DEL the batch
  5. re-apply any flushing batch older than 5 minutes (a crashed run's leftovers)
  6. prune batch ids older than 24h
```

- `V2__create_click_flush_batches.sql` adds the idempotency ledger. V1 stays an exact port.
- **`/stats` clicks** are the cached display total, or on a miss, DB clicks + pending delta.

**Bugs fixed:**
- **B1** (stats showed 0): stats uses DB + pending.
- **B2** (a reset counter overwrote the DB): Redis holds only deltas, and the DB is only ever *added to*.
- **B3** (leaked keys): pending lives in one hash, and delete removes the code's field.
- **B7** (the thread died forever): `@Scheduled` plus catch/log/metric on each run.
- **B8** (`KEYS` blocked Redis): replaced by `RENAME`, with no scan of the whole keyspace.

## Why this approach

**Deltas instead of absolute counts: where does the truth live?** The Python design treated the Redis counter as the truth and *overwrote* Postgres with it every 30s. Any event that lost or reset the Redis value (eviction, TTL expiry, restart without persistence, a click racing a delete) therefore **destroyed history** in the database. With deltas, Postgres is the source of truth, and Redis holds only "what happened since the last flush". The worst a Redis loss can do is lose that delta, never the total. This is the same idea as a write-ahead buffer: an append-only log feeding into durable state.

**`RENAME` as an atomic swap.** The flush must read the pending counts *and* let new clicks continue without losing any that arrive mid-read. The Python approach was `KEYS` (O(N), blocks all of Redis), then N `GET`s, then write. It had a race: a click landing between the GET and the overwrite was lost. `RENAME` moves the whole hash to a new name in O(1). Clicks after that instant create a fresh `pending` hash; clicks before it are all in the batch. There is no lock and no race.

**At-least-once delivery + idempotency = exactly-once effect.** Crashes can happen at any step:
- **Before step 3 commits:** the batch stays in Redis under `flushing:*`. After 5 minutes any instance treats it as an orphan and applies it. Nothing is lost.
- **After step 3 commits but before step 4:** the batch stays in Redis *and* was applied. Re-processing it would double-count, **unless** the batch ID was recorded in the same transaction as the UPDATE. The `ON CONFLICT DO NOTHING` insert returns 0 rows, so the deltas are skipped. The batch ID and the click increments commit atomically together, which is the essence of an idempotency key.
- **Two instances recovering the same orphan:** both try to insert the same batch ID. The unique index makes the second one wait for the first to commit, then its insert returns 0 and it skips. There is no need for a distributed lock (ShedLock or a Redis lock). The database constraint *is* the lock.

**Deadlock avoidance.** Two instances can flush different batches that contain overlapping codes at the same moment. If they lock those rows in different orders, Postgres detects a deadlock and aborts one. The CTE `SELECT … ORDER BY c.id FOR UPDATE` makes every flush lock rows in the same (id) order, so the second flush simply waits. This is the classic "global lock ordering" fix.

**One `UPDATE … FROM unnest()` statement.** The Python comment said "commits everything in one UPDATE", but SQLAlchemy actually sent one UPDATE per changed row. `unnest` turns two arrays into a virtual table, so the whole batch is **one statement and one round trip**, whether it holds 10 codes or 10,000.

**A separate flush pool (2 connections) and a local transaction manager.** The job can't starve request traffic of connections, and vice versa. The `TransactionTemplate` is built inside the job rather than declared as a bean: a second `PlatformTransactionManager` bean would make every plain `@Transactional` ambiguous and switch off Spring Boot's auto-configured JPA transaction manager.

**Redis persistence settings (docker compose; the same values should be set on ElastiCache):**
- `appendonly yes` + `appendfsync everysec`: Redis writes every command to an append-only file, fsynced each second.
- `maxmemory-policy volatile-lru`: under memory pressure, Redis evicts only keys **with a TTL**. `pending` and `flushing:*` deliberately have no TTL, so **un-flushed clicks are never evicted**; cache entries are.

## The data-loss window

| Failure | Clicks lost | Why |
| --- | --- | --- |
| App instance crashes or is killed | **At most the in-flight request's click** | Every click is in Redis before the redirect returns. Nothing is buffered in the JVM. (The Python README said "up to 30s on app crash", which was also wrong for Python: its clicks were in Redis too.) |
| Crash mid-flush | **None** | The batch stays in Redis and is re-applied once (idempotency ledger). |
| Redis process crash, AOF `everysec` | **≤ ~1 second** of clicks | Writes since the last fsync. |
| Redis node lost, no replica or AOF (e.g. ElastiCache without Multi-AZ) | **Everything since the last flush, ≤ 30s** (+ flush duration) | The pending deltas existed only in that node's memory. |
| Redis failover to a replica (async replication) | The replication lag, typically milliseconds | Standard Redis replication is asynchronous. |
| Redis unavailable | **Every click during the outage** (redirects still work) | Counted in `urlshortener.clicks.dropped`. A write-behind to the DB was rejected (below). |
| Postgres unavailable | **None** (delayed) | Batches wait in Redis and are retried as orphans. Memory grows while it's down. |

**Consistency of the displayed number:** `/stats` is eventually consistent. When a cache entry is (re)built, it reads DB clicks from the **replica** (which may lag behind a flush that already removed those clicks from `pending`) and misses batches that are mid-flush. It can therefore briefly **under**-count until the entry expires. The total in Postgres is exact once flushes complete.

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| Keep Python's absolute-count overwrite | It is the data-loss bug (B2). |
| `UPDATE codes SET clicks = clicks + 1` on every redirect | Correct and simple, but it turns the 70% read path into writes on the primary: row-lock contention on hot links and a WAL write per click. The Python project moved away from this for measured reasons. |
| `GETDEL` per key instead of `RENAME` on a hash | Needs one key per code (scanning again) and N round trips. |
| Redis Streams / Kafka event log of clicks | Replayable, and it enables per-click analytics (time, referrer). It is the right choice when you need more than a counter, but it adds a consumer group and ops overhead for a single integer. It's the natural "what's next". |
| ShedLock (one flusher cluster-wide) | Unnecessary: the idempotency ledger makes concurrent flushes safe. It would also add a single point of scheduling. |
| Flush on shutdown (`@PreDestroy`) | Nothing is buffered in the app, so there is nothing to flush. |
| Write clicks directly to the DB while Redis is down | Would move the full redirect write load onto the primary exactly when the system is already degraded. Dropping and counting clicks was chosen as the explicit trade-off for an analytics counter. |
| HyperLogLog | Counts *unique* visitors approximately. The API reports total clicks. |

## What breaks under load or failure, and how to detect it

- **The `{clicks}:pending` hot key.** Every click, from every instance, touches one Redis hash on one shard. A single Redis node handles on the order of 100k HINCRBY/s, and at the README's ~700 RPS this is far from a limit. At cluster scale it becomes one. The fix is to shard the key (`{clicks:0..15}:pending` by hash of code) and flush each shard. Detect it with Redis CPU and command latency on the node that owns the slot.
- **The flush falls behind.** If a flush takes longer than the interval (a huge batch, or a slow primary), the pending hash grows. Detect it with the `urlshortener.clicks.flush` timer (p99 vs 30s), `urlshortener.clicks.flush.batch.codes`, and Redis memory.
- **Orphans accumulating.** Repeated failures (e.g. an integer overflow on `clicks INTEGER` past 2^31) retry forever. Detect it with `urlshortener.clicks.flush.failures` and `urlshortener.clicks.flush.orphans` > 0, which should alert.
- **Row-lock waits on the primary.** The flush's UPDATE holds row locks for milliseconds and only on rows with new clicks. A `POST /shorten` INSERT creates a *new* row and never waits on them, so the README's "sync UPDATE blocks INSERTs" explanation does not apply to this design. Detect it with `pg_stat_activity` wait events and `pg_locks` if p95 spikes line up with flushes.
- **Dropped clicks.** `urlshortener.clicks.dropped` > 0 means Redis was unreachable during redirects.

## Interview questions

1. Why did the absolute-count design lose data, and why can't the delta design lose more than one flush interval's clicks?
2. Walk through a crash at each step of the flush. How does the batch-ID table give exactly-once *effect* on top of at-least-once *delivery*?
3. Why does `RENAME` make the swap race-free, and why do the pending and flushing keys need the same hash tag?
4. How could two concurrent flushes deadlock in PostgreSQL, and how does the `ORDER BY … FOR UPDATE` CTE prevent it?
5. The product team now wants clicks per hour and per country. What changes, and which alternative from the table would you pick?
