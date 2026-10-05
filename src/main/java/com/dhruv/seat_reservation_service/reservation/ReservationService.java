package com.dhruv.seat_reservation_service.reservation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dhruv.seat_reservation_service.common.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

	private final ReservationRepository reservations;

	ReservationService(ReservationRepository reservations) {
		this.reservations = reservations;
	}

	/**
	 * Reserve all of {@code labels} for {@code userId}, or none of them.
	 *
	 * <p>Contract from the controller: {@code labels} are already stripped and free of
	 * duplicates (1-100 of them), and {@code userId} is the token subject.
	 *
	 * <p>One transaction at READ COMMITTED (the Postgres default, which FOR UPDATE relies on
	 * to re-check a row after waiting for its lock):
	 * <ol>
	 * <li>Lock the requested seats that are still available, in label order. A short result
	 * means this request cannot succeed; nothing has been written, so declines are cheap.
	 * <li>Insert the reservation, priced by the database.
	 * <li>Confirm the locked seats. They are ours and available, so all of them update.
	 * </ol>
	 * Any exception rolls back all three steps: no partial reservation is ever visible.
	 *
	 * @throws NotFoundException unknown show (404)
	 * @throws UnknownSeatsException a label is not a seat of the show (400)
	 * @throws SeatsUnavailableException a requested seat is taken (409)
	 */
	@Transactional
	public ReservationResponse reserve(UUID showId, String userId, List<String> labels) {
		List<String> locked = reservations.lockAvailableSeats(showId, labels);
		if (locked.size() < labels.size()) {
			throw declineReason(showId, labels);
		}

		UUID reservationId = UUID.randomUUID();
		long amountPaise = reservations.insertReservation(reservationId, userId, showId, labels.size())
			// Unreachable: we hold locks on seats of this show, and seats reference shows.
			.orElseThrow(() -> new IllegalStateException("Show " + showId + " vanished mid-reservation"));

		int confirmed = reservations.confirmSeats(reservationId, showId, labels);
		if (confirmed != labels.size()) {
			// Also unreachable: the rows are locked by us and were available. If it ever
			// happens it is a bug, so it must surface as a 500, not be disguised as a 409.
			throw new IllegalStateException(
					"Confirmed " + confirmed + " of " + labels.size() + " locked seats for show " + showId);
		}

		return new ReservationResponse(reservationId, showId, userId, labels, amountPaise, "confirmed");
	}

	/** Failure path only: work out which 4xx a short lock result means. */
	private RuntimeException declineReason(UUID showId, List<String> labels) {
		if (!reservations.showExists(showId)) {
			return new NotFoundException("Show " + showId + " not found");
		}
		Set<String> existing = new HashSet<>(reservations.existingLabels(showId, labels));
		List<String> unknown = labels.stream().filter(label -> !existing.contains(label)).toList();
		if (!unknown.isEmpty()) {
			return new UnknownSeatsException(unknown);
		}
		return new SeatsUnavailableException(labels);
	}

}
