package com.dhruv.seat_reservation_service.reservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.dhruv.seat_reservation_service.IntegrationTest;
import com.dhruv.seat_reservation_service.common.NotFoundException;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The real reserve transaction against Postgres, including concurrent attempts to break it. */
class ReserveTests extends IntegrationTest {

	@Autowired
	ReservationService service;

	@Test
	void httpHappyPathReturns201WithDatabasePrice() throws Exception {
		UUID show = createShow(300, "A1", "A2", "A3");
		mvc.perform(post("/shows/{id}/reserve", show).header("Authorization", bearer("alice"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"seats\": [\"A2\", \"A1\"]}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.user_id").value("alice"))
			.andExpect(jsonPath("$.amount_paise").value(600))
			.andExpect(jsonPath("$.status").value("confirmed"));
		assertThat(seatOwners(show)).containsOnlyKeys("A1", "A2");
		assertInvariant(show);
	}

	@Test
	void declineReasons() {
		UUID show = createShow(100, "A1", "A2");
		service.reserve(show, "bob", List.of("A2"), null);

		assertThatThrownBy(() -> service.reserve(UUID.randomUUID(), "alice", List.of("A1"), null))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.reserve(show, "alice", List.of("A1", "Z9"), null))
			.isInstanceOf(UnknownSeatsException.class);
		assertThatThrownBy(() -> service.reserve(show, "alice", List.of("A1", "A2"), null))
			.isInstanceOf(SeatsUnavailableException.class);
	}

	@Test
	void allOrNothingLeavesNoTraceOnDecline() {
		UUID show = createShow(100, "A1", "A2");
		service.reserve(show, "bob", List.of("A2"), null);
		assertThatThrownBy(() -> service.reserve(show, "alice", List.of("A1", "A2"), null))
			.isInstanceOf(SeatsUnavailableException.class);

		assertThat(seatOwners(show)).containsOnlyKeys("A2"); // A1 still available
		assertThat(reservationCount(show)).isEqualTo(1); // alice's attempt left no row
		assertInvariant(show);
	}

	@Test
	void hotSeatStormHasExactlyOneWinner() throws Exception {
		UUID show = createShow(100, "A1", "A2");
		int attempts = 200;
		List<Outcome> outcomes = race(attempts, i -> service.reserve(show, "user-" + i, List.of("A1"), null));

		assertThat(outcomes).filteredOn(o -> o == Outcome.CONFIRMED).hasSize(1);
		assertThat(outcomes).filteredOn(o -> o == Outcome.SEAT_TAKEN).hasSize(attempts - 1);
		assertThat(reservationCount(show)).isEqualTo(1);
		assertInvariant(show);
	}

	/**
	 * Overlapping multi-seat requests in shuffled order: without one global lock order this
	 * is the classic deadlock. Any deadlock would surface as an unexpected exception.
	 */
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(ints = { 1, 2, 3, 4, 5 })
	void overlappingMultiSeatRequestsNeverDeadlockOrDoubleBook(int round) throws Exception {
		// Few seats, many requests: maximise overlapping lock sets.
		List<String> labels = IntStream.rangeClosed(1, 4).mapToObj(i -> "S" + i).toList();
		UUID show = createShow(100, labels.toArray(String[]::new));
		Random random = new Random(round);
		List<List<String>> requests = new ArrayList<>();
		for (int i = 0; i < 400; i++) {
			List<String> shuffled = new ArrayList<>(labels);
			Collections.shuffle(shuffled, random);
			requests.add(List.copyOf(shuffled.subList(0, 2 + random.nextInt(2))));
		}
		Map<UUID, List<String>> won = new ConcurrentHashMap<>();

		List<Outcome> outcomes = race(requests.size(), i -> {
			ReservationResponse r = service.reserve(show, "user-" + i, requests.get(i), null).response();
			won.put(r.reservationId(), r.seats());
		});

		assertThat(outcomes).doesNotContain(Outcome.UNEXPECTED);
		// No seat appears in two winning reservations, and the DB agrees with the winners.
		Set<String> claimed = new HashSet<>();
		won.values().forEach(seats -> seats.forEach(seat -> assertThat(claimed.add(seat)).isTrue()));
		Map<String, UUID> owners = seatOwners(show);
		assertThat(owners.keySet()).isEqualTo(claimed);
		won.forEach((id, seats) -> seats.forEach(seat -> assertThat(owners.get(seat)).isEqualTo(id)));
		assertThat(reservationCount(show)).isEqualTo(won.size());
		assertInvariant(show);
	}

	// --- helpers

	enum Outcome {
		CONFIRMED, SEAT_TAKEN, UNEXPECTED
	}

	interface Attempt {

		void run(int i) throws Exception;

	}

	/** Runs every attempt on its own thread, all released at once by a start gate. */
	private List<Outcome> race(int count, Attempt attempt) throws Exception {
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch start = new CountDownLatch(1);
		List<Outcome> outcomes = new CopyOnWriteArrayList<>();
		List<Throwable> unexpected = new CopyOnWriteArrayList<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int n = i;
				futures.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					try {
						attempt.run(n);
						outcomes.add(Outcome.CONFIRMED);
					}
					catch (SeatsUnavailableException ex) {
						outcomes.add(Outcome.SEAT_TAKEN);
					}
					catch (Throwable ex) {
						outcomes.add(Outcome.UNEXPECTED);
						unexpected.add(ex);
					}
					return null;
				}));
			}
			ready.await(10, TimeUnit.SECONDS);
			start.countDown();
			for (Future<?> f : futures) {
				f.get(60, TimeUnit.SECONDS);
			}
		}
		assertThat(unexpected).as("unexpected exceptions (deadlocks, 5xx-class bugs)").isEmpty();
		return outcomes;
	}

	private UUID createShow(long pricePaise, String... labels) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO shows (id, name, price_paise, total_seats) VALUES (?, 'Reserve', ?, ?)")
			.params(id, pricePaise, labels.length)
			.update();
		jdbc.sql("INSERT INTO seats (show_id, label) SELECT ?, unnest(CAST(? AS text[]))").params(id, labels).update();
		return id;
	}

	private Map<String, UUID> seatOwners(UUID show) {
		Map<String, UUID> owners = new java.util.HashMap<>();
		jdbc.sql("SELECT label, reservation_id FROM seats WHERE show_id = ? AND status = 'confirmed'")
			.param(show)
			.query((rs, n) -> owners.put(rs.getString(1), rs.getObject(2, UUID.class)))
			.list();
		return owners;
	}

	private long reservationCount(UUID show) {
		return jdbc.sql("SELECT count(*) FROM reservations WHERE show_id = ?").param(show).query(Long.class).single();
	}

	/** available + held + confirmed == total_seats, read in one statement. */
	private void assertInvariant(UUID show) {
		boolean holds = jdbc.sql("""
				SELECT s.total_seats = count(st.*) FILTER (WHERE st.status IN ('available', 'held', 'confirmed'))
				FROM shows s JOIN seats st ON st.show_id = s.id
				WHERE s.id = ?
				GROUP BY s.total_seats
				""").param(show).query(Boolean.class).single();
		assertThat(holds).isTrue();
	}

}
