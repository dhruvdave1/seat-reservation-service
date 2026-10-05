package com.dhruv.seat_reservation_service.reservation;

import java.util.List;
import java.util.UUID;

import com.dhruv.seat_reservation_service.common.InvalidRequestException;
import com.dhruv.seat_reservation_service.common.NotFoundException;
import com.dhruv.seat_reservation_service.common.SeatLabels;
import com.dhruv.seat_reservation_service.common.TransientRetry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReservationController {

	private static final Logger log = LoggerFactory.getLogger(ReservationController.class);

	private final ReservationService service;

	private final CancelService cancelService;

	private final TransientRetry retry;

	private final ReservationMetrics metrics;

	ReservationController(ReservationService service, CancelService cancelService, TransientRetry retry,
			ReservationMetrics metrics) {
		this.service = service;
		this.cancelService = cancelService;
		this.retry = retry;
		this.metrics = metrics;
	}

	/**
	 * The user is the token's subject; nothing in the body can change who acts. A replay
	 * returns the original 201 body unchanged. Each attempt the retry makes is a new
	 * transaction (the service is the transactional proxy). Metrics are recorded only after
	 * the outcome is final.
	 */
	@PostMapping("/shows/{showId}/reserve")
	@ResponseStatus(HttpStatus.CREATED)
	ReservationResponse reserve(@PathVariable UUID showId, @AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
			@Valid @RequestBody ReserveRequest request) {
		List<String> labels = SeatLabels.normalize(request.seats());
		String key = idempotencyKey(headerKey, request.idempotencyKey());
		String userId = jwt.getSubject();
		try {
			ReservationService.ReserveResult result = retry.run(() -> service.reserve(showId, userId, labels, key));
			if (result.replayed()) {
				metrics.declined("idempotent_replay");
			}
			else {
				metrics.confirmed(labels.size());
			}
			log.atInfo()
				.addKeyValue("outcome", result.replayed() ? "idempotent_replay" : "confirmed")
				.addKeyValue("show_id", showId)
				.addKeyValue("user_id", userId)
				.addKeyValue("reservation_id", result.response().reservationId())
				.addKeyValue("seats", labels.size())
				.log("reserve");
			return result.response();
		}
		catch (ErrorResponseException ex) {
			String reason = reason(ex);
			metrics.declined(reason);
			log.atInfo()
				.addKeyValue("outcome", reason)
				.addKeyValue("show_id", showId)
				.addKeyValue("user_id", userId)
				.addKeyValue("seats", labels.size())
				.log("reserve declined");
			throw ex;
		}
	}

	/** Owner-only: the caller is the token subject, compared with the reservation's user in SQL. */
	@PostMapping("/reservations/{reservationId}/cancel")
	ReservationResponse cancel(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt jwt) {
		ReservationResponse response = retry.run(() -> cancelService.cancel(reservationId, jwt.getSubject()));
		metrics.cancelled();
		log.atInfo()
			.addKeyValue("reservation_id", reservationId)
			.addKeyValue("user_id", jwt.getSubject())
			.addKeyValue("seats", response.seats().size())
			.log("reservation cancelled");
		return response;
	}

	private static String reason(ErrorResponseException ex) {
		Object reason = ex.getBody().getProperties() == null ? null : ex.getBody().getProperties().get("reason");
		if (reason != null) {
			return reason.toString();
		}
		return ex instanceof NotFoundException ? "unknown_show" : "other";
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
