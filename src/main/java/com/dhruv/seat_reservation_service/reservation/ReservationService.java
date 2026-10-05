package com.dhruv.seat_reservation_service.reservation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dhruv.seat_reservation_service.common.NotFoundException;
import com.dhruv.seat_reservation_service.idempotency.IdempotencyMismatchException;
import com.dhruv.seat_reservation_service.idempotency.IdempotencyRepository;
import com.dhruv.seat_reservation_service.idempotency.RequestHash;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

	private final ReservationRepository reservations;

	private final IdempotencyRepository idempotency;

	private final JsonMapper json;

	ReservationService(ReservationRepository reservations, IdempotencyRepository idempotency, JsonMapper json) {
		this.reservations = reservations;
		this.idempotency = idempotency;
		this.json = json;
	}

	/** The 201 body, and whether it is a replay of an earlier request with the same key. */
	public record ReserveResult(ReservationResponse response, boolean replayed) {
	}

	/**
	 * Reserve all of {@code labels} for {@code userId}, or none of them.
	 *
	 * <p>Contract from the controller: {@code labels} are already stripped and free of
	 * duplicates (1-100 of them), {@code userId} is the token subject, and
	 * {@code idempotencyKey} is null or a validated key.
	 *
	 * <p>One transaction at READ COMMITTED (the Postgres default, which FOR UPDATE relies on
	 * to re-check a row after waiting for its lock). Locks are always taken in the same
	 * order (idempotency key, then seats in label order), so reserves cannot deadlock:
	 * <ol>
	 * <li>Claim the idempotency key. A committed duplicate is replayed (same body) or
	 * rejected (different body); an in-flight duplicate makes us wait for its outcome.
	 * <li>Lock the requested seats that are still available, in label order. A short result
	 * means this request cannot succeed; nothing durable has been written.
	 * <li>Insert the reservation, priced by the database.
	 * <li>Confirm the locked seats. They are ours and available, so all of them update.
	 * <li>Store the response on the key, so retries replay it.
	 * </ol>
	 * Any exception rolls back every step, the key claim included: declines are not stored,
	 * so retrying a declined request evaluates it again.
	 *
	 * @throws NotFoundException unknown show (404)
	 * @throws UnknownSeatsException a label is not a seat of the show (400)
	 * @throws SeatsUnavailableException a requested seat is taken (409)
	 * @throws IdempotencyMismatchException key reused with a different request (409)
	 */
	@Transactional
	public ReserveResult reserve(UUID showId, String userId, List<String> labels, String idempotencyKey) {
		if (idempotencyKey != null) {
			String hash = RequestHash.of(showId, labels);
			if (!idempotency.claim(userId, idempotencyKey, hash)) {
				IdempotencyRepository.StoredKey stored = idempotency.find(userId, idempotencyKey);
				if (!stored.requestHash().equals(hash)) {
					throw new IdempotencyMismatchException();
				}
				return new ReserveResult(json.readValue(stored.response(), ReservationResponse.class), true);
			}
		}

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

		ReservationResponse response = new ReservationResponse(reservationId, showId, userId, labels, amountPaise,
				"confirmed");
		if (idempotencyKey != null) {
			idempotency.complete(userId, idempotencyKey, reservationId, json.writeValueAsString(response));
		}
		return new ReserveResult(response, false);
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
