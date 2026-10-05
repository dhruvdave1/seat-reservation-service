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
import java.util.stream.IntStream;

import com.dhruv.seat_reservation_service.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PerUserLimitTests extends IntegrationTest {

	@Autowired
	ReservationService service;

	/** The spec's check: 10 parallel reserves by one user on limit 4 end with at most 4 seats. */
	@RepeatedTest(3)
	void tenParallelReservesOnLimitFourGiveExactlyFour() throws Exception {
		UUID show = createShow(null, IntStream.rangeClosed(1, 10).mapToObj(i -> "S" + i).toArray(String[]::new));
		CountDownLatch start = new CountDownLatch(1);
		List<String> outcomes = new CopyOnWriteArrayList<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 1; i <= 10; i++) {
				String seat = "S" + i;
				futures.add(pool.submit(() -> {
					start.await();
					try {
						service.reserve(show, "greedy", List.of(seat), null);
						outcomes.add("confirmed");
					}
					catch (PerUserLimitException ex) {
						outcomes.add("per_user_limit");
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> f : futures) {
				f.get(30, TimeUnit.SECONDS);
			}
		}
		assertThat(outcomes).filteredOn("confirmed"::equals).hasSize(4);
		assertThat(outcomes).filteredOn("per_user_limit"::equals).hasSize(6);
		assertThat(confirmedSeatsOf(show, "greedy")).isEqualTo(4);
		assertThat(userSeatCount(show, "greedy")).isEqualTo(4);
	}

	@Test
	void limitCountsAcrossReservationsAndIsA409() throws Exception {
		UUID show = createShow(null, "A1", "A2", "A3", "A4", "A5", "A6");
		service.reserve(show, "alice", List.of("A1", "A2", "A3"), null);
		mvc.perform(post("/shows/{id}/reserve", show).header("Authorization", bearer("alice"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"seats\": [\"A4\", \"A5\"]}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("per_user_limit"));
		// the declined attempt left A4/A5 free and the count unchanged
		assertThat(confirmedSeatsOf(show, "alice")).isEqualTo(3);
		service.reserve(show, "alice", List.of("A4"), null);
		assertThat(userSeatCount(show, "alice")).isEqualTo(4);
		// other users are unaffected
		service.reserve(show, "bob", List.of("A5", "A6"), null);
	}

	@Test
	void oneRequestAboveTheLimitIsDeclined() throws Exception {
		UUID show = createShow(null, "A1", "A2", "A3", "A4", "A5");
		assertThatThrownBy(() -> service.reserve(show, "alice", List.of("A1", "A2", "A3", "A4", "A5"), null))
			.isInstanceOf(PerUserLimitException.class);
		assertThat(confirmedSeatsOf(show, "alice")).isZero();
	}

	@Test
	void showCanSetItsOwnLimit() throws Exception {
		UUID show = createShow(2, "A1", "A2", "A3");
		service.reserve(show, "alice", List.of("A1", "A2"), null);
		assertThatThrownBy(() -> service.reserve(show, "alice", List.of("A3"), null))
			.isInstanceOf(PerUserLimitException.class);
	}

	@Test
	void seatTakenIsReportedBeforeTheLimit() throws Exception {
		UUID show = createShow(1, "A1", "A2");
		service.reserve(show, "bob", List.of("A1"), null);
		service.reserve(show, "alice", List.of("A2"), null);
		// alice is at her limit, but A1 is taken anyway: seat_taken is the cheaper, truer answer
		assertThatThrownBy(() -> service.reserve(show, "alice", List.of("A1"), null))
			.isInstanceOf(SeatsUnavailableException.class);
	}

	private UUID createShow(Integer limit, String... labels) throws Exception {
		String seats = "[\"" + String.join("\", \"", labels) + "\"]";
		String limitField = limit == null ? "" : ", \"per_user_limit\": " + limit;
		String body = mvc.perform(post("/shows").header("Authorization", adminBearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Limit\", \"price_paise\": 100, \"seats\": " + seats + limitField + "}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.per_user_limit").value(limit == null ? 4 : limit))
			.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	private long confirmedSeatsOf(UUID show, String user) {
		return jdbc.sql("""
				SELECT count(*) FROM seats st JOIN reservations r ON r.id = st.reservation_id
				WHERE st.show_id = ? AND r.user_id = ? AND st.status = 'confirmed'
				""").params(show, user).query(Long.class).single();
	}

	private int userSeatCount(UUID show, String user) {
		return jdbc.sql("SELECT seat_count FROM user_show_seats WHERE show_id = ? AND user_id = ?")
			.params(show, user).query(Integer.class).single();
	}

}
