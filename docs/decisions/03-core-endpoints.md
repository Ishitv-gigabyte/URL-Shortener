# 03: Core endpoints, short codes, validation, error handling

## What was built

- **Layers:**
  - `ShortUrlController` handles HTTP: parse, map to DTOs, status codes.
  - `ShortUrlService` holds the rules: normalise the URL, retry on collisions, 404s.
  - `ShortCodeRepository` does the SQL.
  - The DTOs (`ShortenRequest`, `ShortUrlResponse`, `StatsResponse`) are records, separate from the `ShortCode` entity.
  - The service returns its own small records (`ShortenedUrl`, `UrlStats`), so it knows nothing about JSON.
- **`ShortCodeGenerator`:** 10 random characters from Python's exact alphabet (`ascii_letters + digits`), drawn with `SecureRandom`.
- **`ApiExceptionHandler`:** reproduces FastAPI's error bodies: `{"detail": "..."}` for 404/405/500, and Pydantic's 422 list for validation.
- **Golden fixtures:** before writing any of this, the Python service was run in Docker Compose and 23 request/response pairs were recorded to `src/test/resources/golden/python-responses.txt`. `ErrorContractIT` asserts the Java responses **byte-for-byte** against them.
- **Swagger UI** is served at `/docs` and the OpenAPI spec at `/openapi.json` (springdoc), the same URLs FastAPI used.

### Fidelity details that needed explicit code

| Behaviour | Why Spring's default differs | Fix |
| --- | --- | --- |
| `created_at` as `2026-01-02T03:04:05.120000`, and no fraction when the microseconds are 0 | Jackson would print `.12` | `Timestamps.format` |
| `Location: https://example.com/a%20b/%C3%BC` | `URI.create` throws on spaces and non-ASCII | `LocationHeader.encode` replicates Python's `quote()` with Starlette's safe set |
| `{"original_url": 123}` → 422 `string_type` | Jackson silently coerces 123 to `"123"` | `StrictStringDeserializer` |
| `null` → `string_type`, absent → `missing` | Jackson treats both as null | `getNullValue` throws; `getAbsentValue` returns null so `@NotNull` fires |
| The 422 echoes the whole body as `input` | The body stream is consumed by the time the error handler runs | `RequestBodyCachingFilter` (a `ContentCachingRequestWrapper` limited to 16 KB, only on `POST /shorten`) |
| `text/plain` body → 422, not 415 | Spring rejects the content type before reading | Handler for `HttpMediaTypeNotSupportedException` |
| `/stats/abc/` → 307 to `/stats/abc` | Spring 6+ doesn't match trailing slashes at all | `TrailingSlashRedirectFilter` |
| Unknown path → `{"detail":"Not Found"}` | Spring renders its own error JSON | The fallback handler renders any `ErrorResponse` as `{"detail": <reason phrase>}` |

### Remaining differences, accepted and documented

| Case | Python | Java | Why accepted |
| --- | --- | --- | --- |
| Malformed JSON `ctx.error` | `"Expecting value"` (Python `json` module text) | `"Invalid JSON"` | Parser-specific wording. Type, loc, msg and input match. |
| `Allow` header on 405 | Methods of the *first* route whose path matched (`PUT /shorten` → `GET`) | All methods on the path (`POST`) | Starlette's value is an artefact of route order. Spring's is correct. |
| `HEAD /{code}` | 405 | Same status and headers as GET, with no body | Spring follows the HTTP spec: HEAD must be supported wherever GET is. |
| `DELETE` 204 headers | Includes `content-type: application/json` | No content type | A 204 has no body, so a content type means nothing. |
| `/docs` | 200 HTML | 302 → `/swagger-ui/index.html` (then 200) | springdoc's design. Browsers follow it transparently. ReDoc (`/redoc`) is not served. |

### Bug fixes in this phase

- **B4:** codes that aren't `[A-Za-z0-9]{1,32}` are rejected with 404 **before** any lookup. With the new Redis design (Phase 4) this also keeps user input out of Redis key names.
- **B5:** a URL that passes the 1500-character check but exceeds it once `https://` is added now gets a 422 `string_too_long`. The Python service returned a plain-text 500 from Postgres.
- **B9:** `SecureRandom` instead of a predictable PRNG.

## Why this approach

**Random codes, not base62 encoding of an ID.** Encoding the auto-increment `id` gives short codes with no collisions, but it makes every link enumerable (`/1`, `/2`, …) and leaks the creation count. Random 10-character codes give 62^10 ≈ 8.4 × 10^17 possibilities. By the birthday bound, collisions only become likely around √(8.4 × 10^17) ≈ 9 × 10^8 codes, and each collision costs one retried INSERT. The **unique constraint is the actual guarantee**; the retry loop only handles the rare collision.

**Each retry is its own transaction.** The service is not `@Transactional`, and `repository.save()` opens and commits its own transaction. If the loop ran inside one outer transaction, the first unique-constraint failure would abort that transaction. PostgreSQL rejects every statement after an error until rollback (`current transaction is aborted`), and Spring would also mark it rollback-only. Retries would then be impossible. `ShortCodeCollisionIT` forces this path with a scripted generator.

**Separate DTOs from the entity.** The JSON field names (`original_url`), formats (`created_at` string) and field order are API contract. The entity's shape is storage. Keeping them apart means a schema change can't silently change the API, and vice versa.

**Error handling in one `@RestControllerAdvice`.** All mapping from exceptions to responses is in one file, so the contract can be read in one place. Domain exceptions (`ShortCodeNotFoundException`, `CodeGenerationException`, `UrlTooLongException`) carry meaning, not HTTP status codes; the handler decides the status.

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| `Base62.encode(id)` or Snowflake-style IDs | Enumerable, and a behaviour change from the Python service. A good answer to "how would you avoid retries at 100k writes/s?", but not needed here. |
| A pre-generated key pool (a "Key Generation Service") | Removes collisions and retries, but adds a component with its own failure modes. Overkill at this scale. |
| `ProblemDetail` (RFC 9457) error bodies | The modern Spring default, but it would break the FastAPI contract. |
| `@JsonFormat` on a `LocalDateTime` field | Can't express "6 digits, or none when zero". A plain `String` field with an explicit formatter is obvious to read. |
| Following Pydantic less strictly (just 422 + any body) | "Exact contract" was a requirement. The golden-fixture approach made it cheap to verify. |
| MockMvc for API tests | Filters and real HTTP behaviour (redirect handling, headers) are exercised better over a real socket. Tests use a small `java.net.http` client. |

## What breaks under load or failure, and how to detect it

- **Collision rate grows with table size.** At ~10^9 rows, retries become measurable. Detect it with a counter on collisions (log/metric in the retry loop) and p99 of `POST /shorten`. Fix it with longer codes or pre-generated keys.
- **Hot shortens on the primary.** Every create is an INSERT on the primary, and write throughput is bounded by the primary's pool (30 per instance) and disk. Detect it with `hikaricp.connections.pending{pool=primary}` and p95 of `http.server.requests{uri=/shorten}`.
- **Replica lag → 404 right after create.** A redirect before replication hits the replica and 404s. The Phase 4 cache write on shorten hides this for the common case. Detect it with a 404-rate spike on `GET /{shortCode}` correlated with the RDS `ReplicaLag` metric.
- **Large request bodies.** Only 16 KB is cached for error echoing, and Tomcat's default max POST size bounds the rest. A flood of large bodies costs parsing CPU. Detect it with request size metrics at the ALB.
- **Unexpected exceptions** return plain-text 500 and are logged at ERROR with the stack trace. Detect them with `http.server.requests{status=500}` and the error-log rate.

## Interview questions

1. Why generate random codes instead of base62-encoding the database ID? At what scale would you change the design, and to what?
2. Walk through what happens in PostgreSQL and in Spring if the collision-retry loop runs inside a single `@Transactional` method.
3. How do you prove a migration preserved an API contract? What did the golden-fixture approach catch here that reading the Python code would have missed?
4. Where should the line between a controller, a service and a repository be? Point to something in this codebase that sits on that line.
5. The redirect encodes `Location` by hand. What would happen with `ResponseEntity.created(URI.create(url))`, and what's the security concern with echoing user-supplied URLs in headers?
