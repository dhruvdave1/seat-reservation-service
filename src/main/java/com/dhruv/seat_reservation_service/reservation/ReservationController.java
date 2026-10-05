package com.dhruv.seat_reservation_service.reservation;

import java.util.List;
import java.util.UUID;

import com.dhruv.seat_reservation_service.common.SeatLabels;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReservationController {

	private final ReservationService service;

	ReservationController(ReservationService service) {
		this.service = service;
	}

	/** The user is the token's subject; nothing in the body can change who acts. */
	@PostMapping("/shows/{showId}/reserve")
	@ResponseStatus(HttpStatus.CREATED)
	ReservationResponse reserve(@PathVariable UUID showId, @AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody ReserveRequest request) {
		List<String> labels = SeatLabels.normalize(request.seats());
		return service.reserve(showId, jwt.getSubject(), labels);
	}

}
