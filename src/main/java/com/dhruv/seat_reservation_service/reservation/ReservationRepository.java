package com.dhruv.seat_reservation_service.reservation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReservationRepository {
    private final JdbcClient jdbcClient;

    public ReservationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public record Reservation(
            UUID id,
            String userId,
            UUID showId,
            long amountPaise,
            String status,                 // 'confirmed' | 'cancelled'
            OffsetDateTime createdAt,
            OffsetDateTime cancelledAt) {}

    /**
     * Inserts a confirmed reservation priced from the show row (price * seat count) and
     * returns the amount the database computed. Empty when the show does not exist.
     */
    public Optional<Long> insertReservation(UUID reservationId, String userId, UUID showId, int seatCount) {
        return jdbcClient.sql("""
                        INSERT INTO reservations (id, user_id, show_id, amount_paise, status)
                        SELECT :reservationId, :userId, s.id, s.price_paise * :seatCount, 'confirmed'
                        FROM shows s
                        WHERE s.id = :showId
                        RETURNING amount_paise
                        """)
                .param("reservationId", reservationId)
                .param("userId", userId)
                .param("seatCount", seatCount)
                .param("showId", showId)
                .query(Long.class)
                .optional();
    }

    /**
     * Row-locks the listed seats that are still available, in label order. The lock step
     * runs above the sort, so every transaction locks overlapping seats in the same order:
     * no lock cycles, so no deadlocks between multi-seat requests. Seats already taken are
     * filtered out without being locked, so requests for a sold seat do not queue on it.
     * A row locked by an in-flight transaction is waited on and re-checked after it commits.
     * Returns the labels locked; fewer than requested means a seat is taken or does not exist.
     */
    public List<String> lockAvailableSeats(UUID showId, List<String> labels) {
        return jdbcClient.sql("""
                        SELECT label
                        FROM seats
                        WHERE show_id = :showId
                          AND label = ANY(CAST(:labels AS text[]))
                          AND status = 'available'
                        ORDER BY label COLLATE "C"
                        FOR UPDATE
                        """)
                .param("showId", showId)
                .param("labels", labels.toArray(String[]::new))
                .query(String.class)
                .list();
    }

    /**
     * Confirms every listed seat that is still available. Returns how many were confirmed;
     * anything less than labels.size() means at least one seat was taken or does not exist.
     */
    public int confirmSeats(UUID reservationId, UUID showId, List<String> labels) {
        return jdbcClient.sql("""
                        UPDATE seats
                        SET status = 'confirmed',
                            reservation_id = :reservationId
                        WHERE show_id = :showId
                          AND label = ANY(CAST(:labels AS text[]))
                          AND status = 'available'
                        """)
                .param("reservationId", reservationId)
                .param("showId", showId)
                .param("labels", labels.toArray(String[]::new))
                .update();
    }

    /**
     * Adds {@code seatCount} to the user's seats for this show if the result stays within
     * the show's per-user limit. Returns false (nothing written) when it would not.
     *
     * <p>First reservation: the INSERT creates the row, and its SELECT yields no row when
     * seatCount alone exceeds the limit. Later ones: ON CONFLICT locks the user's row, so
     * one user's concurrent reserves are applied one at a time, each seeing the committed
     * count of the previous one. The WHERE makes the increment conditional; the table's
     * CHECK would reject an over-limit count even if this query were wrong.
     */
    public boolean addUserSeats(UUID showId, String userId, int seatCount) {
        return jdbcClient.sql("""
                        INSERT INTO user_show_seats (show_id, user_id, seat_count, seat_limit)
                        SELECT s.id, :userId, :seatCount, s.per_user_limit
                        FROM shows s
                        WHERE s.id = :showId AND :seatCount <= s.per_user_limit
                        ON CONFLICT (show_id, user_id) DO UPDATE
                        SET seat_count = user_show_seats.seat_count + EXCLUDED.seat_count
                        WHERE user_show_seats.seat_count + EXCLUDED.seat_count <= user_show_seats.seat_limit
                        """)
                .param("showId", showId)
                .param("userId", userId)
                .param("seatCount", seatCount)
                .update() == 1;
    }

    // Failure path only: called after lockAvailableSeats came back short, to tell the
    // client why. A successful reserve never runs these.

    /** True if the show exists (404 otherwise). */
    public boolean showExists(UUID showId) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM shows WHERE id = :showId)")
                .param("showId", showId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Which of the requested labels are real seats of this show, whatever their status.
     * Any requested label missing from the result is unknown (400); if none are missing,
     * the short lock means a seat is taken (409).
     */
    public List<String> existingLabels(UUID showId, List<String> labels) {
        return jdbcClient.sql("""
                        SELECT label
                        FROM seats
                        WHERE show_id = :showId
                          AND label = ANY(CAST(:labels AS text[]))
                        """)
                .param("showId", showId)
                .param("labels", labels.toArray(String[]::new))
                .query(String.class)
                .list();
    }
}
