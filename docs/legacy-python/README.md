# Legacy: Python/FastAPI implementation

The service was originally written in Python (FastAPI, SQLAlchemy, redis-py). It was migrated to Spring Boot;
see [MIGRATION_PLAN.md](../../MIGRATION_PLAN.md) and [docs/decisions](../decisions). The Python source is
in git history. The last commit containing it is tagged in the migration's final commit message.

| File | What it is | Caveat |
| --- | --- | --- |
| [AWS_ARCHITECTURE.md](AWS_ARCHITECTURE.md), [ArchitectureImage.png](ArchitectureImage.png) | How the Python service was deployed on AWS | Hand-built infrastructure; no IaC in the repo |
| [locust-results/](locust-results) | Locust screenshots from the course's Sections 1–4 | Screenshots only: no raw CSVs, and no commit hash or run parameters recorded per run. Not comparable with the Spring service. |

None of these numbers apply to the current implementation. See "Load testing" in the main README for how to
measure it.
