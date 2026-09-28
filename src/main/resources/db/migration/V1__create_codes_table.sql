-- Exact port of the schema SQLAlchemy's Base.metadata.create_all() produced for app/models.py.
-- Verified by diffing pg_dump --schema-only of both databases (see docs/decisions/02-schema-and-data-access.md).
--
-- Notes carried over from the Python model:
--   * clicks has no DEFAULT: SQLAlchemy applied default=0 in Python, not in the DDL.
--   * created_at is TIMESTAMP WITHOUT TIME ZONE holding UTC wall-clock time.
--   * short_code_chars is an unbounded VARCHAR; the UNIQUE constraint's index is the lookup index.
CREATE TABLE codes (
    id               SERIAL                      NOT NULL,
    clicks           INTEGER                     NOT NULL,
    short_code_chars VARCHAR                     NOT NULL,
    original_url     VARCHAR(1500)               NOT NULL,
    created_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT codes_pkey PRIMARY KEY (id),
    CONSTRAINT codes_short_code_chars_key UNIQUE (short_code_chars)
);
