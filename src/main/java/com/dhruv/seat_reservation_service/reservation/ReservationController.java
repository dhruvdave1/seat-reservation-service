package com.dhruv.seat_reservation_service.reservation;

import java.util.List;
import java.util.UUID;

import com.dhruv.seat_reservation_service.common.InvalidRequestException;
import com.dhruv.seat_reservation_service.common.SeatLabels;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReservationController {

	private final ReservationService service;

	ReservationController(ReservationService service) {
		this.service = service;
	}

	/**
	 * The user is the token's subject; nothing in the body can change who acts. A replay
	 * returns the original 201 body unchanged.
	 */
	@PostMapping("/shows/{showId}/reserve")
	@ResponseStatus(HttpStatus.CREATED)
	ReservationResponse reserve(@PathVariable UUID showId, @AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
			@Valid @RequestBody ReserveRequest request) {
		List<String> labels = SeatLabels.normalize(request.seats());
		String key = idempotencyKey(headerKey, request.idempotencyKey());
		return service.reserve(showId, jwt.getSubject(), labels, key).response();
	}

	private static String idempotencyKey(String header, String body) {
		if (header != null && !header.matches(ReserveRequest.KEY_PATTERN)) {
			throw new InvalidRequestException("Idempotency-Key must be 1-128 printable ASCII characters");
		}
		if (header != null && body != null && !header.equals(body)) {
			throw new InvalidRequestException("Idempotency-Key header and idempotency_key body field differ");
		}
		return header != null ? header : body;
	}

}
