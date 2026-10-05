package com.dhruv.seat_reservation_service.reservation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CancelRepository {

	private final JdbcClient jdbc;

	CancelRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record Cancelled(UUID showId, long amountPaise) {
	}

	record Existing(String userId, String status) {
	}

	/**
	 * Flips the reservation to cancelled only if it is this user's and still confirmed.
	 * The row lock serializes concurrent cancels of one reservation: the second waits, then
	 * re-checks status = 'confirmed' against the committed row and matches nothing.
	 */
	Optional<Cancelled> cancelIfOwnedAndConfirmed(UUID reservationId, String userId) {
		return jdbc.sql("""
				UPDATE reservations
				SET status = 'cancelled', cancelled_at = now()
				WHERE id = :id AND user_id = :userId AND status = 'confirmed'
				RETURNING show_id, amount_paise
				""")
			.param("id", reservationId)
			.param("userId", userId)
			.query(Cancelled.class)
			.optional();
	}

	/**
	 * Frees exactly the seats this reservation holds. Matching on reservation_id means a seat
	 * that was released and re-booked by someone else can never be touched from here.
	 */
	List<String> releaseSeats(UUID reservationId) {
		return jdbc.sql("""
				UPDATE seats
				SET status = 'available', reservation_id = NULL
				WHERE reservation_id = :id
				RETURNING label
				""")
			.param("id", reservationId)
			.query(String.class)
			.list();
	}

	/** Gives the released seats back to the user's per-show allowance. */
	int subtractUserSeats(UUID showId, String userId, int seatCount) {
		return jdbc.sql("""
				UPDATE user_show_seats
				SET seat_count = seat_count - :seatCount
				WHERE show_id = :showId AND user_id = :userId
				""")
			.param("seatCount", seatCount)
			.param("showId", showId)
			.param("userId", userId)
			.update();
	}

	/** Failure path only: why the cancel matched nothing. */
	Optional<Existing> find(UUID reservationId) {
		return jdbc.sql("SELECT user_id, status FROM reservations WHERE id = :id")
			.param("id", reservationId)
			.query(Existing.class)
			.optional();
	}

}
