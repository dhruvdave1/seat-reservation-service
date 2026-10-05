package com.dhruv.seat_reservation_service.reservation;

import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Business counters, incremented only after the transaction has committed (or definitely
 * declined), so they agree with what the API returned. Prometheus names:
 * reservations_confirmed_total, reservations_declined_total{reason}, reservations_cancelled_total,
 * seats_confirmed_total.
 */
@Component
public class ReservationMetrics {

	/** Every decline reason, registered up front so each series exists (at 0) from boot. */
	static final List<String> REASONS = List.of("seat_taken", "per_user_limit", "idempotent_replay", "unknown_seat",
			"unknown_show", "idempotency_mismatch");

	private final MeterRegistry registry;

	private final Counter confirmed;

	private final Counter seatsConfirmed;

	private final Counter cancelled;

	ReservationMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.confirmed = Counter.builder("reservations.confirmed")
			.description("Reservations committed (one per successful reserve, replays excluded)")
			.register(registry);
		this.seatsConfirmed = Counter.builder("seats.confirmed.by.reserve")
			.description("Seats confirmed by committed reservations")
			.register(registry);
		this.cancelled = Counter.builder("reservations.cancelled")
			.description("Reservations cancelled by their owner")
			.register(registry);
		REASONS.forEach(this::declinedCounter);
	}

	void confirmed(int seats) {
		confirmed.increment();
		seatsConfirmed.increment(seats);
	}

	void declined(String reason) {
		declinedCounter(reason).increment();
	}

	void cancelled() {
		cancelled.increment();
	}

	private Counter declinedCounter(String reason) {
		return Counter.builder("reservations.declined")
			.description("Reserve requests that did not create a reservation, by reason")
			.tag("reason", reason)
			.register(registry);
	}

}
