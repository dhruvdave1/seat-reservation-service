-- V2__idempotency_keys.sql (Postgres)
-- Keys are scoped per user. The row is claimed at the start of the reserve transaction,
-- so a concurrent duplicate blocks on the primary key until the first attempt ends.
-- Only successful reservations keep their key: a declined attempt rolls the row back.

CREATE TABLE idempotency_keys (
    user_id          TEXT        NOT NULL,
    idempotency_key  TEXT        NOT NULL CHECK (char_length(idempotency_key) BETWEEN 1 AND 128),
    request_hash     TEXT        NOT NULL,   -- sha-256 of show id + sorted seat labels
    reservation_id   UUID        REFERENCES reservations (id),
    response         TEXT,                   -- original 201 body, replayed verbatim
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idempotency_key),
    -- a committed key always carries its result
    CHECK ((reservation_id IS NULL) = (response IS NULL))
);
