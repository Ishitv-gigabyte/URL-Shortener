-- Idempotency ledger for ClickFlushJob. A batch of click deltas is applied to codes.clicks in the same
-- transaction that inserts its batch_id here; if the same batch is ever re-processed (a crash between the
-- DB commit and deleting the batch from Redis, or two instances recovering the same orphan), the INSERT
-- conflicts and the deltas are not applied twice. Rows older than app.clicks.batch-retention are pruned.
CREATE TABLE click_flush_batches (
    batch_id   VARCHAR(64) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT click_flush_batches_pkey PRIMARY KEY (batch_id)
);

CREATE INDEX click_flush_batches_applied_at_idx ON click_flush_batches (applied_at);
