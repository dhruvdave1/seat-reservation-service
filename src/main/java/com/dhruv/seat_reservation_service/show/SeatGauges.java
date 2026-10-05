package com.dhruv.seat_reservation_service.show;

import java.time.Duration;
import java.time.Instant;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * seats_available, seats_held, seats_confirmed across all shows, read from the database so
 * they reconcile with GET /shows/{id} by construction. All three come from one statement
 * (one snapshot), so available + held + confirmed always equals the number of seats. The
 * snapshot is reused for one second so a scrape costs one query, not three.
 */
@Component
class SeatGauges {

	private static final Duration MAX_AGE = Duration.ofSeconds(1);

	private final JdbcClient jdbc;

	private Snapshot snapshot = new Snapshot(0, 0, 0, Instant.EPOCH);

	SeatGauges(JdbcClient jdbc, MeterRegistry registry) {
		this.jdbc = jdbc;
		Gauge.builder("seats.available", this, g -> g.current().available()).description("Seats available").register(registry);
		Gauge.builder("seats.held", this, g -> g.current().held()).description("Seats held").register(registry);
		Gauge.builder("seats.confirmed", this, g -> g.current().confirmed()).description("Seats confirmed").register(registry);
	}

	record Snapshot(long available, long held, long confirmed, Instant at) {
	}

	private synchronized Snapshot current() {
		if (Duration.between(snapshot.at(), Instant.now()).compareTo(MAX_AGE) > 0) {
			snapshot = jdbc.sql("""
					SELECT count(*) FILTER (WHERE status = 'available') AS available,
					       count(*) FILTER (WHERE status = 'held')      AS held,
					       count(*) FILTER (WHERE status = 'confirmed') AS confirmed
					FROM seats
					""")
				.query((rs, n) -> new Snapshot(rs.getLong("available"), rs.getLong("held"), rs.getLong("confirmed"),
						Instant.now()))
				.single();
		}
		return snapshot;
	}

}
