package com.dhruv.seat_reservation_service.reservation;

import java.util.List;
import java.util.UUID;

import com.dhruv.seat_reservation_service.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Each query on its own against Postgres. Concurrency is tested at the service level. */
class ReservationRepositoryTests extends IntegrationTest {

	@Autowired
	ReservationRepository repo;

	private UUID showId;

	@BeforeEach
	void createShow() {
		showId = UUID.randomUUID();
		jdbc.sql("INSERT INTO shows (id, name, price_paise, total_seats) VALUES (?, 'Repo', 250, 4)").param(showId).update();
		jdbc.sql("INSERT INTO seats (show_id, label) SELECT ?, unnest(ARRAY['B1', 'A2', 'A10', 'A1'])").param(showId).update();
	}

	@Test
	void insertReservationPricesFromTheShowAndIsEmptyForUnknownShow() {
		UUID id = UUID.randomUUID();
		assertThat(repo.insertReservation(id, "alice", showId, 3)).contains(750L);
		assertThat(jdbc.sql("SELECT amount_paise FROM reservations WHERE id = ?").param(id).query(Long.class).single())
			.isEqualTo(750);
		assertThat(repo.insertReservation(UUID.randomUUID(), "alice", UUID.randomUUID(), 1)).isEmpty();
	}

	@Test
	void lockReturnsOnlyAvailableRequestedSeatsInByteOrder() {
		confirm("B1");
		assertThat(repo.lockAvailableSeats(showId, List.of("B1", "A2", "A10", "Z9"))).containsExactly("A10", "A2");
	}

	@Test
	void confirmSeatsUpdatesOnlyAvailableOnes() {
		confirm("A1");
		UUID id = UUID.randomUUID();
		repo.insertReservation(id, "bob", showId, 2);
		assertThat(repo.confirmSeats(id, showId, List.of("A1", "A2"))).isEqualTo(1);
		assertThat(jdbc.sql("SELECT reservation_id FROM seats WHERE show_id = ? AND label = 'A2'")
			.param(showId).query(UUID.class).single()).isEqualTo(id);
	}

	@Test
	void failurePathChecks() {
		confirm("A1");
		assertThat(repo.showExists(showId)).isTrue();
		assertThat(repo.showExists(UUID.randomUUID())).isFalse();
		// taken seats still exist; unknown labels do not
		assertThat(repo.existingLabels(showId, List.of("A1", "Z9", "B1"))).containsExactlyInAnyOrder("A1", "B1");
	}

	private void confirm(String label) {
		UUID id = UUID.randomUUID();
		repo.insertReservation(id, "other", showId, 1);
		jdbc.sql("UPDATE seats SET status = 'confirmed', reservation_id = ? WHERE show_id = ? AND label = ?")
			.params(id, showId, label)
			.update();
	}

}
