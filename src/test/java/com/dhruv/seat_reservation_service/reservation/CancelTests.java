package com.dhruv.seat_reservation_service.reservation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.dhruv.seat_reservation_service.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.ErrorResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CancelTests extends IntegrationTest {

	@Autowired
	ReservationService reservations;

	@Autowired
	CancelService cancels;

	@Test
	void ownerCancelReleasesSeatsForRebooking() throws Exception {
		UUID show = createShow(4, "A1", "A2", "A3");
		UUID id = reservations.reserve(show, "alice", List.of("A2", "A1"), null).response().reservationId();

		cancel(id, "alice").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("cancelled"))
			.andExpect(jsonPath("$.seats[0]").value("A1"))
			.andExpect(jsonPath("$.seats[1]").value("A2"))
			.andExpect(jsonPath("$.amount_paise").value(200));

		assertThat(jdbc.sql("SELECT status FROM reservations WHERE id = ?").param(id).query(String.class).single())
			.isEqualTo("cancelled");
		assertThat(seatStatus(show, "A1")).isEqualTo("available");
		assertThat(userSeatCount(show, "alice")).isZero();
		// cleanly re-bookable by someone else
		reservations.reserve(show, "bob", List.of("A1", "A2"), null);
		assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
	}

	@Test
	void onlyTheOwnerCanCancel() throws Exception {
		UUID show = createShow(4, "A1");
		UUID id = reservations.reserve(show, "alice", List.of("A1"), null).response().reservationId();
		cancel(id, "mallory").andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("not_owner"));
		assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
		cancel(UUID.randomUUID(), "alice").andExpect(status().isNotFound());
		cancel(id, null).andExpect(status().isUnauthorized());
	}

	/** A stale cancel must never free a seat that now belongs to someone else. */
	@Test
	void secondCancelCannotResurrectAReBookedSeat() throws Exception {
		UUID show = createShow(4, "A1");
		UUID alices = reservations.reserve(show, "alice", List.of("A1"), null).response().reservationId();
		cancel(alices, "alice").andExpect(status().isOk());
		UUID bobs = reservations.reserve(show, "bob", List.of("A1"), null).response().reservationId();

		cancel(alices, "alice").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("already_cancelled"));
		assertThat(jdbc.sql("SELECT reservation_id FROM seats WHERE show_id = ? AND label = 'A1'")
			.param(show).query(UUID.class).single()).isEqualTo(bobs);
	}

	@Test
	void cancelFreesPerUserAllowance() {
		UUID show = createShowQuietly(1, "A1", "A2");
		UUID id = reservations.reserve(show, "alice", List.of("A1"), null).response().reservationId();
		cancels.cancel(id, "alice");
		reservations.reserve(show, "alice", List.of("A2"), null); // within limit 1 again
		assertThat(userSeatCount(show, "alice")).isEqualTo(1);
	}

	@Test
	void concurrentCancelsOfOneReservationSucceedOnce() throws Exception {
		UUID show = createShowQuietly(4, "A1", "A2");
		UUID id = reservations.reserve(show, "alice", List.of("A1", "A2"), null).response().reservationId();
		List<String> outcomes = race(20, i -> cancels.cancel(id, "alice"));
		assertThat(outcomes).filteredOn("ok"::equals).hasSize(1);
		assertThat(outcomes).filteredOn("409"::equals).hasSize(19);
		assertThat(userSeatCount(show, "alice")).isZero();
	}

	/** Cancel racing a reserve storm on the same seat: at most one new owner, invariant intact. */
	@Test
	void cancelDuringAReserveStorm() throws Exception {
		UUID show = createShowQuietly(4, "A1");
		UUID id = reservations.reserve(show, "alice", List.of("A1"), null).response().reservationId();
		List<String> outcomes = race(101, i -> {
			if (i == 0) {
				cancels.cancel(id, "alice");
			}
			else {
				reservations.reserve(show, "user-" + i, List.of("A1"), null);
			}
		});
		assertThat(outcomes).doesNotContain("unexpected");
		long owners = jdbc.sql("SELECT count(*) FROM seats WHERE show_id = ? AND status = 'confirmed'")
			.param(show).query(Long.class).single();
		long confirmedReservations = jdbc.sql("""
				SELECT count(*) FROM reservations WHERE show_id = ? AND status = 'confirmed'
				""").param(show).query(Long.class).single();
		assertThat(owners).isLessThanOrEqualTo(1);
		assertThat(confirmedReservations).isEqualTo(owners);
	}

	interface Attempt {

		void run(int i) throws Exception;

	}

	private List<String> race(int count, Attempt attempt) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<String> outcomes = new CopyOnWriteArrayList<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int n = i;
				futures.add(pool.submit(() -> {
					start.await();
					try {
						attempt.run(n);
						outcomes.add("ok");
					}
					catch (SeatsUnavailableException ex) {
						outcomes.add("seat_taken");
					}
					catch (ErrorResponseException ex) {
						outcomes.add(String.valueOf(ex.getStatusCode().value()));
					}
					catch (Throwable ex) {
						outcomes.add("unexpected");
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> f : futures) {
				f.get(30, TimeUnit.SECONDS);
			}
		}
		return outcomes;
	}

	private ResultActions cancel(UUID id, String user) throws Exception {
		var request = post("/reservations/{id}/cancel", id);
		if (user != null) {
			request.header("Authorization", bearer(user));
		}
		return mvc.perform(request);
	}

	private UUID createShow(int limit, String... labels) throws Exception {
		String seats = "[\"" + String.join("\", \"", labels) + "\"]";
		String body = mvc.perform(post("/shows").header("Authorization", adminBearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Cancel\", \"price_paise\": 100, \"per_user_limit\": " + limit + ", \"seats\": "
					+ seats + "}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	private UUID createShowQuietly(int limit, String... labels) {
		try {
			return createShow(limit, labels);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String seatStatus(UUID show, String label) {
		return jdbc.sql("SELECT status FROM seats WHERE show_id = ? AND label = ?").params(show, label)
			.query(String.class).single();
	}

	private int userSeatCount(UUID show, String user) {
		return jdbc.sql("SELECT seat_count FROM user_show_seats WHERE show_id = ? AND user_id = ?")
			.params(show, user).query(Integer.class).single();
	}

}
