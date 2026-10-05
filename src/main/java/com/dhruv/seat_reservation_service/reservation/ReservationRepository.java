package com.dhruv.seat_reservation_service.reservation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
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
     * Inserts a confirmed reservation priced from the show row (price * seat count).
     * Returns 0 when the show does not exist, 1 otherwise.
     */
    public int insertReservation(UUID reservationId, String userId, UUID showId, int seatCount) {
        return jdbcClient.sql("""
                        INSERT INTO reservations (id, user_id, show_id, amount_paise, status)
                        SELECT :reservationId, :userId, s.id, s.price_paise * :seatCount, 'confirmed'
                        FROM shows s
                        WHERE s.id = :showId
                        """)
                .param("reservationId", reservationId)
                .param("userId", userId)
                .param("seatCount", seatCount)
                .param("showId", showId)
                .update();
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
}
