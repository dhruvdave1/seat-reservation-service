package com.dhruv.seat_reservation_service.reservation;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

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
	 * <p>Expected outcomes: return the 201 body; throw {@code NotFoundException} for an
	 * unknown show (404); throw {@link SeatsUnavailableException} when any seat cannot be
	 * confirmed (409). Throwing rolls the transaction back.
	 */
	public ReservationResponse reserve(UUID showId, String userId, List<String> labels) {
		// TODO(dhruv): the reserve transaction is mine to write (CLAUDE.md working agreement).
		throw new UnsupportedOperationException("reserve not implemented yet");
	}

}
