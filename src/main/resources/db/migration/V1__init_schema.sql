-- V1__init_schema.sql (Postgres)
-- Create order matters: shows -> reservations -> seats (seats FK-references both).

CREATE TABLE shows (
                       id           UUID        PRIMARY KEY,
                       name         TEXT        NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 200),
                       price_paise  BIGINT      NOT NULL CHECK (price_paise > 0),
                       total_seats  INTEGER     NOT NULL CHECK (total_seats > 0),
                       created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Rows are INSERTED on reserve (status = 'confirmed').
-- Status only changes on cancel: confirmed -> cancelled, with cancelled_at set.
CREATE TABLE reservations (
                              id            UUID        PRIMARY KEY,   -- generated in the app, so it can be put on seats
                              user_id       TEXT        NOT NULL CHECK (char_length(user_id) BETWEEN 1 AND 64),
                              show_id       UUID        NOT NULL REFERENCES shows (id),
                              amount_paise  BIGINT      NOT NULL CHECK (amount_paise > 0),
                              status        TEXT        NOT NULL DEFAULT 'confirmed'
                                  CHECK (status IN ('confirmed', 'cancelled')),
                              created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                              cancelled_at  TIMESTAMPTZ,
    -- cancelled_at is set if and only if the reservation is cancelled
                              CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL)),
    -- target for seats' composite FK (a FK needs a unique constraint on exactly these columns)
                              UNIQUE (id, show_id)
);

CREATE TABLE seats (
                       show_id         UUID NOT NULL REFERENCES shows (id),
                       label           TEXT NOT NULL CHECK (char_length(label) BETWEEN 1 AND 16),
                       status          TEXT NOT NULL DEFAULT 'available'
                           CHECK (status IN ('available', 'held', 'confirmed')),
                       reservation_id  UUID,
                       PRIMARY KEY (show_id, label),
    -- a seat can only point at a reservation for the same show.
    -- MATCH SIMPLE skips the check while reservation_id is NULL (available seats).
                       FOREIGN KEY (reservation_id, show_id) REFERENCES reservations (id, show_id),
    -- available <=> no reservation id. A confirmed seat always has one.
                       CHECK ((status = 'available') = (reservation_id IS NULL))
);

-- Cancel releases seats by reservation_id. Partial: most seats are available (NULL),
-- so only currently-confirmed seats are indexed.
CREATE INDEX idx_seats_reservation_id ON seats (reservation_id)
    WHERE reservation_id IS NOT NULL;