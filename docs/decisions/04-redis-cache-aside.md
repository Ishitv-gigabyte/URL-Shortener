# 04: Redis cache-aside for redirects

## What was built

`UrlCache` is the only class that knows Redis key names, TTLs and scripts. `ShortUrlService` uses it cache-aside: check the cache, fall back to Postgres on a miss, then fill the cache.

| Key | Type | TTL | Written by | Removed by |
| --- | --- | --- | --- | --- |
| `code:{X}` | HASH `url`, `created_at`, `clicks` | 1h ± 10% | shorten; redirect/stats miss (seed script) | delete; expiry; eviction |
| `miss:{X}` | STRING | 60s | lookup of a code that doesn't exist; **delete (tombstone)** | expiry; shorten of that code |
| `rev:<sha256(url)>` | STRING → code | 24h | shorten | delete; expiry |

**Request flows:**
- **Redirect:** `HGET code:{X} url`. On a hit, return 302. On a miss, check `miss:{X}` (404 if present), then query the **replica** (if absent, set `miss:{X}` and 404), then seed `code:{X}` and return 302.
- **Stats:** the same, with `HGETALL`.
- **Shorten:** check `rev:` → `code:` (→ primary if `code:` is gone), then INSERT, then fill `code:{X}` and `rev:`.
- **Delete:** read the row on the primary → `DELETE` (commits) → **then** remove `code:{X}` and `rev:`, and set the `miss:{X}` tombstone.

**Bugs fixed:**
- **B4:** keys are prefixed, and codes are validated before they reach a key name.
- **B6:** every Redis call is wrapped; on failure it's counted, logged, and the request falls back to Postgres.
- **The README's "created_at evicted" crash** is structurally impossible: one hash per code means eviction removes all fields together.

## Why this approach

**One hash per code instead of three strings.** The Python service stored `{code}`, `created_at:{code}` and `clicks:{code}` as three independent keys. Redis evicts *keys*, so under memory pressure any one of them could vanish while the others survived. That caused the README's 500 error, and it is why the Python stats path had fallback logic for every combination. A hash is one key, so it is all there or all gone. It also costs one round trip (`HGETALL`) instead of three.

**The seed is a Lua script.** It does "only if absent and not tombstoned: HSET + EXPIRE" atomically. Redis runs a script without interleaving other commands, so:
- **Two concurrent misses can't clobber each other.** The first writer wins. That matters in Phase 5, when the hash also holds a live click count.
- **There is never a hash without a TTL.** Separate HSET then EXPIRE calls could crash between the two and leave an immortal key.
- **A stale replica read can't resurrect a deleted code** (next point).

**Delete, then invalidate, then tombstone.** This is the classic cache-aside race:
1. Request A misses the cache and reads the row from the replica.
2. Request B deletes the row and invalidates the cache.
3. Request A writes its now-stale row into the cache.

The deleted link then keeps working for up to an hour. The Python service had this race, made worse because it invalidated *before* committing. Here the delete commits first, and the tombstone `miss:{X}` makes step 3's seed script refuse. The window is closed as long as replica lag plus request time is under the tombstone TTL (60s). If lag exceeds that, you have bigger problems, and it is alertable.

**Hash tags (`{X}`).** In Redis Cluster, a key's shard is chosen by hashing only the part inside `{}`. `code:{X}` and `miss:{X}` therefore always land on the same shard, which is required for the seed script to read both. Without the tag, the script fails with `CROSSSLOT` the day you move to cluster mode. It's free on a single node.

**Negative caching (`miss:{X}`).** Without it, every request for a non-existent code goes to the database. Bots and scanners probing random codes could then push unlimited load through the cache (**cache penetration**). The trade-off is that a row that appears later stays invisible for up to 60s. Shorten clears the marker for the codes it creates, so this only affects rows that appear some other way, such as replication catching up after a 404.

**TTL jitter.** 1h ± 10%. Entries created together, for example during a launch spike, would otherwise all expire in the same second, and their simultaneous misses would all hit Postgres at once (**cache stampede / thundering herd**). Jitter spreads those misses over about 12 minutes.

**Hashing the reverse key.** A URL can be 1500 bytes, and Redis keys cost memory and are compared on every lookup. SHA-256 gives a fixed 68-byte key and keeps raw user input out of key names.

**Fail-fast timeouts (250ms).** Lettuce's default command timeout is 60s. With Redis down, every request would hang for a minute, Tomcat's threads would fill up, and the app would go down *because of its cache*. With 250ms, a Redis outage costs about a quarter-second per request on the fallback path. `RedisDownIT` points the app at a closed port and checks that all four endpoints still work.

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| Spring's `@Cacheable` / `@CacheEvict` | Can't express multi-key writes, conditional seeding, tombstones or per-key TTL jitter. It hides the ordering of cache and DB operations, which is the interesting part. It also has the proxy pitfall: a `@Cacheable` method called from the same class bypasses the cache silently. |
| Keep Python's key layout (for a mixed Python/Java fleet) | Rejected because the Phase 5 click model is incompatible anyway, and the old layout has the partial-eviction bug. Cutover is stop-the-world instead: stop Python, run its last sync, then start Java. |
| Write-through (update the cache in the same code path as the DB) | Only shorten writes, and it already fills the cache. For a read-mostly workload, cache-aside with a TTL is simpler. |
| Distributed lock / single-flight on cache miss (prevent stampede) | Adds a lock round trip on every miss. Jitter plus a small replica pool is enough here. It's the next step if a single viral link's misses ever overload the replica. |
| Bloom filter of existing codes (instead of `miss:`) | Stops penetration with no false negatives, but needs rebuilding on delete (standard Bloom filters can't remove) and a Redis module or custom code. Negative caching is simpler at this scale. |
| Circuit breaker (Resilience4j) around Redis | Would skip Redis entirely after repeated failures, saving the 250ms per request. It's a reasonable follow-up. Timeouts plus fallback already make the system correct, and the breaker would only optimise the outage case. |
| Local in-process cache (Caffeine) in front of Redis | Great for a handful of viral links, but each instance caches independently, so invalidation on delete needs pub/sub. Deferred. |

## What breaks under load or failure, and how to detect it

- **Redis down or slow.** Every request pays up to 250ms, then goes to Postgres. With all traffic on Postgres, the replica pool (30) can saturate: the fallback becomes the bottleneck. Detect it with `urlshortener.cache.errors` rate > 0, `hikaricp.connections.pending{pool=replica}`, and `/actuator/health` showing `redis: DOWN`. Alert on the error rate, not the health check.
- **Hit rate collapses** (e.g. after a Redis restart, since the cache starts cold). Detect it with `urlshortener.cache.lookups{result=miss}` / total. A restart shows as a spike that decays over the TTL.
- **Memory pressure.** Redis starts evicting (with `volatile-lru`, only keys with a TTL; see Phase 5). Detect it with Redis `evicted_keys` and `used_memory` vs `maxmemory` (ElastiCache `Evictions`, `DatabaseMemoryUsagePercentage`).
- **Replica lag > 60s.** The tombstone expires before the replica applies the delete, and a deleted link could be re-cached. Detect it with the RDS `ReplicaLag` metric and alert well below 60s.
- **Hot key.** One viral code means all its requests hit one Redis shard. A single Redis node handles ~100k simple ops/s, so this is far off. Detect it with `redis-cli --hotkeys` or per-shard CPU. The fix is a local cache tier.
- **Negative-cache false 404s.** A row that appears via replication after a 404 stays hidden up to 60s. Detect it with `urlshortener.cache.lookups{result=negative_hit}` rising after deploys, or user reports of "new link 404s".

## Interview questions

1. Describe the cache-aside race between a delete and a concurrent cache miss. How does this design close it, and under what conditions does it re-open?
2. Why does the seed use a Lua script instead of `HSETNX` or `MULTI`/`EXEC`? What guarantees does Redis give a script, and what are the costs of long scripts?
3. What are cache penetration, cache stampede and hot keys? Which mitigations are implemented here and which would you add next?
4. Redis goes down at peak traffic. Walk through what users see, what the database sees, and which metric pages you first.
5. What are Redis Cluster hash tags, and what would break if `code:{X}` and `miss:{X}` were named `code:X` and `miss:X`?
