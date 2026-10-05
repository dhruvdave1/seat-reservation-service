-- init.sql (Postgres)
-- Create order matters: shows -> reservations -> seats (seats FK-references both).

CREATE TABLE shows (
    id           UUID        PRIMARY KEY,
    name         TEXT        NOT NULL,
    price_paise  BIGINT      NOT NULL CHECK (price_paise >= 0),
    total_seats  INTEGER     NOT NULL CHECK (total_seats > 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Rows are INSERTED on reserve (status = 'confirmed').
-- Status only changes on cancel: confirmed -> canceled, with canceled_at set.
CREATE TABLE reservations (
    id            UUID        PRIMARY KEY,   -- generated in the app, so it can be put on seats
    user_id       TEXT        NOT NULL,
    show_id       UUID        NOT NULL REFERENCES shows (id),
    amount_paise  BIGINT      NOT NULL CHECK (amount_paise >= 0),
    status        TEXT        NOT NULL DEFAULT 'confirmed'
                  CHECK (status IN ('confirmed', 'cancelled')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at  TIMESTAMPTZ,
    -- canceled_at is set if and only if the reservation is canceled
    CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL))
);

CREATE TABLE seats (
    show_id         UUID NOT NULL REFERENCES shows (id),
    label           TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'available'
                    CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id  UUID REFERENCES reservations (id),
    PRIMARY KEY (show_id, label),
    -- available <=> no reservation id. A confirmed seat always has one.
    CHECK ((status = 'available') = (reservation_id IS NULL))
);

-- Cancel releases seats by reservation_id; without this it scans the table.
CREATE INDEX idx_seats_reservation_id ON seats (reservation_id);

-- Lookups by user (e.g. list my reservations)
CREATE INDEX idx_reservations_user_id ON reservations (user_id);
