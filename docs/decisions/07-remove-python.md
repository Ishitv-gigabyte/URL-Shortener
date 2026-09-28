# 07: Remove the Python implementation

## What was done

- Deleted `app/`, `tests/`, `requirements.in`, `requirements.txt`, `.flake8`, `.vscode/settings.json` and the Python CI workflow (`run-ci.yml`). Python-only entries were removed from `.gitignore`.
- Kept `locustfile.py` unchanged. It is the load-test contract, and CI runs it against the Java service.
- `docs/legacy-python/README.md` names the last commit that contains the Python code, so it can be restored with `git checkout <commit> -- app tests`.

**Gate before deleting** (as the brief required): a clean `./mvnw clean verify` (18 unit + 68 integration tests) passed, and a fresh `docker compose up --build` smoke test (shorten → redirect → stats → delete) passed, on the same tree that was committed.

## Why a dedicated final commit

- **Reviewability:** the diff is pure deletion, so a reviewer can confirm nothing Java-side changed.
- **Reversibility:** `git revert` of one commit brings the Python service back without touching the Java code.
- **Bisectability:** every earlier commit has both implementations, so the migration can be compared side by side at any point in history.

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| Delete Python in the same commit as the last Java feature | Mixes "add" and "remove" in one diff. A revert would take both. |
| Keep Python in a `legacy/` folder | Dead code rots, confuses search results and newcomers, and CI would have to either keep testing it or ignore it. Git history already keeps it. |
| Archive to a separate branch | Equivalent to history plus a named commit, with one more thing to maintain. |

## What could break, and how to detect it

- **Something outside the repo still runs the Python entry point** (e.g. an EC2 user-data script running `uvicorn app.main:app`). The infrastructure was hand-built with no IaC, so the repo cannot show this. Detect it with deploys failing at start and health checks failing after the first deploy of this commit. Mitigation: switch the deploy to the Docker image before merging.
- **Env var names changed** (`DATABASE_URL` → `DB_PRIMARY_URL` + `DB_USERNAME`/`DB_PASSWORD`). An old `.env` makes the app fall back to `localhost` defaults and fail to connect at startup. That fails fast because Hikari and Flyway connect during boot. Detect it in startup logs with `Connection refused` from Flyway.
- **Redis keys from the Python era** (`{code}`, `clicks:{code}`, `created_at:{code}`) remain in a shared Redis but are never read. Old Python click counters that were never synced would be lost. Cutover procedure: stop Python, wait for its final 30s sync, then `FLUSHDB` or let the TTLs expire. Detect them with `redis-cli --scan --pattern 'clicks:*'`.

## Interview questions

1. How do you decide when it's safe to delete the old implementation in a migration? What evidence did you require here?
2. Walk through a zero-downtime cutover from the Python fleet to the Java fleet, given that the two use incompatible Redis click models.
3. What would you do differently if the two implementations had to run side by side behind the same load balancer for a week?
