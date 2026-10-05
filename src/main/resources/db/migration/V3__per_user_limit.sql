-- V3__per_user_limit.sql (Postgres)
-- Per-user seat limit per show, enforced by the database itself.

ALTER TABLE shows
    ADD COLUMN per_user_limit INTEGER NOT NULL DEFAULT 4 CHECK (per_user_limit BETWEEN 1 AND 100);

-- One row per (show, user): how many seats the user currently holds for the show.
-- seat_limit is copied from the show when the row is created, because a CHECK can only
-- see its own row. The CHECK is the backstop; the reserve path's conditional upsert
-- declines first, and the row lock it takes serializes one user's concurrent reserves.
CREATE TABLE user_show_seats (
    show_id     UUID    NOT NULL REFERENCES shows (id),
    user_id     TEXT    NOT NULL,
    seat_count  INTEGER NOT NULL,
    seat_limit  INTEGER NOT NULL,
    PRIMARY KEY (show_id, user_id),
    CHECK (seat_count BETWEEN 0 AND seat_limit)
);

-- Backfill for reservations made before this migration. GREATEST keeps the CHECK
-- satisfiable if anyone already holds more than the default limit.
INSERT INTO user_show_seats (show_id, user_id, seat_count, seat_limit)
SELECT r.show_id, r.user_id, count(*), GREATEST(sh.per_user_limit, count(*))
FROM seats st
JOIN reservations r ON r.id = st.reservation_id
JOIN shows sh ON sh.id = r.show_id
WHERE st.status = 'confirmed' AND r.status = 'confirmed'
GROUP BY r.show_id, r.user_id, sh.per_user_limit;
