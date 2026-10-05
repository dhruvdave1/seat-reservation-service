package com.dhruv.seat_reservation_service.reservation;

import java.util.List;
import java.util.UUID;

/** 201 body of POST /shows/{id}/reserve, in the shape the spec gives. */
public record ReservationResponse(UUID reservationId, UUID showId, String userId, List<String> seats,
		long amountPaise, String status) {
}
