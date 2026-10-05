package com.dhruv.seat_reservation_service.reservation;

import java.util.List;
import java.util.UUID;

import com.dhruv.seat_reservation_service.common.NotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

@Service
public class CancelService {

	private final CancelRepository cancels;

	CancelService(CancelRepository cancels) {
		this.cancels = cancels;
	}

	/**
	 * Owner-only cancel in one transaction. Locks are taken as: reservation row, its seats,
	 * then the user's seat-count row. Reserve takes seats (only available ones) and then the
	 * user row, and never touches confirmed seats or existing reservations, so the two cannot
	 * wait on each other in a cycle.
	 *
	 * @throws NotFoundException no such reservation (404)
	 * @throws ErrorResponseException 403 not_owner, or 409 already_cancelled
	 */
	@Transactional
	public ReservationResponse cancel(UUID reservationId, String userId) {
		CancelRepository.Cancelled cancelled = cancels.cancelIfOwnedAndConfirmed(reservationId, userId)
			.orElseThrow(() -> declineReason(reservationId, userId));

		List<String> released = cancels.releaseSeats(reservationId).stream().sorted().toList();
		if (cancels.subtractUserSeats(cancelled.showId(), userId, released.size()) != 1) {
			// Every confirmed reservation was counted when made (or by the V3 backfill).
			throw new IllegalStateException("No seat count row for user " + userId + " on show " + cancelled.showId());
		}
		return new ReservationResponse(reservationId, cancelled.showId(), userId, released, cancelled.amountPaise(),
				"cancelled");
	}

	private RuntimeException declineReason(UUID reservationId, String userId) {
		CancelRepository.Existing existing = cancels.find(reservationId).orElse(null);
		if (existing == null) {
			return new NotFoundException("Reservation " + reservationId + " not found");
		}
		if (!existing.userId().equals(userId)) {
			return problem(HttpStatus.FORBIDDEN, "Only the owner can cancel this reservation", "not_owner");
		}
		return problem(HttpStatus.CONFLICT, "Reservation is already cancelled", "already_cancelled");
	}

	private static ErrorResponseException problem(HttpStatus status, String detail, String reason) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("reason", reason);
		return new ErrorResponseException(status, problem, null);
	}

}
