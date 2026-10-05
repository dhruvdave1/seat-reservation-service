package com.dhruv.seat_reservation_service.reservation;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /shows/{id}/reserve. No user field: identity comes only from the token.
 * idempotency_key arrives in phase 4.
 */
public record ReserveRequest(
		@NotEmpty @Size(max = ReserveRequest.MAX_SEATS) List<@NotBlank @Size(max = 16) String> seats) {

	/** Bounds the lock and update arrays; the per-user limit (phase 5) is far lower anyway. */
	public static final int MAX_SEATS = 100;

}
