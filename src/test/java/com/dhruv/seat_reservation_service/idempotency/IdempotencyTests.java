package com.dhruv.seat_reservation_service.idempotency;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.dhruv.seat_reservation_service.IntegrationTest;
import com.dhruv.seat_reservation_service.reservation.ReservationService;
import com.dhruv.seat_reservation_service.reservation.SeatsUnavailableException;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdempotencyTests extends IntegrationTest {

	@Autowired
	ReservationService service;

	@Test
	void sameKeySameBodyReplaysTheOriginal201() throws Exception {
		UUID show = createShow("A1", "A2", "A3");
		String first = reserve(show, "alice", "k-1", "[\"A1\", \"A2\"]").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		// same seats in another order is the same request
		reserve(show, "alice", "k-1", "[\"A2\", \"A1\"]").andExpect(status().isCreated())
			.andExpect(content().json(first, org.springframework.test.json.JsonCompareMode.STRICT));

		assertThat(reservationCount(show)).isEqualTo(1);
	}

	@Test
	void sameKeyDifferentBodyIs409() throws Exception {
		UUID show = createShow("A1", "A2");
		reserve(show, "alice", "k-2", "[\"A1\"]").andExpect(status().isCreated());
		reserve(show, "alice", "k-2", "[\"A2\"]").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("idempotency_mismatch"));
		// a different show with the same key is also a different request
		reserve(createShow("A1"), "alice", "k-2", "[\"A1\"]").andExpect(status().isConflict());
		assertThat(seatStatus(show, "A2")).isEqualTo("available");
	}

	@Test
	void keysAreScopedPerUser() throws Exception {
		UUID show = createShow("A1", "A2");
		reserve(show, "alice", "shared", "[\"A1\"]").andExpect(status().isCreated());
		reserve(show, "bob", "shared", "[\"A2\"]").andExpect(status().isCreated())
			.andExpect(jsonPath("$.user_id").value("bob"));
	}

	@Test
	void headerKeyWorksAndConflictingHeaderAndBodyIs400() throws Exception {
		UUID show = createShow("A1");
		mvc.perform(post("/shows/{id}/reserve", show).header("Authorization", bearer("alice"))
			.header("Idempotency-Key", "h-1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"seats\": [\"A1\"], \"idempotency_key\": \"other\"}")).andExpect(status().isBadRequest());
		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/shows/{id}/reserve", show).header("Authorization", bearer("alice"))
				.header("Idempotency-Key", "h-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"seats\": [\"A1\"]}")).andExpect(status().isCreated());
		}
		assertThat(reservationCount(show)).isEqualTo(1);
		reserve(show, "alice", "has space", "[\"A1\"]").andExpect(status().isBadRequest());
	}

	@Test
	void declinesAreNotStoredSoARetryIsEvaluatedAgain() throws Exception {
		UUID show = createShow("A1", "A2");
		reserve(show, "bob", null, "[\"A1\"]").andExpect(status().isCreated());
		reserve(show, "alice", "k-3", "[\"A1\"]").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("seat_taken"));
		// the declined attempt left no key behind, so the key is free for a new request
		reserve(show, "alice", "k-3", "[\"A2\"]").andExpect(status().isCreated());
	}

	/** A client retrying aggressively: every duplicate gets the one reservation. */
	@Test
	void concurrentDuplicatesReserveExactlyOnce() throws Exception {
		UUID show = createShow("A1", "A2");
		int duplicates = 30;
		CountDownLatch start = new CountDownLatch(1);
		Set<UUID> reservationIds = ConcurrentHashMap.newKeySet();
		Set<Boolean> replayFlags = ConcurrentHashMap.newKeySet();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> futures = new java.util.ArrayList<>();
			for (int i = 0; i < duplicates; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					ReservationService.ReserveResult r = service.reserve(show, "alice", List.of("A1", "A2"), "storm");
					reservationIds.add(r.response().reservationId());
					replayFlags.add(r.replayed());
					return null;
				}));
			}
			start.countDown();
			for (Future<?> f : futures) {
				f.get(30, TimeUnit.SECONDS);
			}
		}
		assertThat(reservationIds).hasSize(1);
		assertThat(replayFlags).containsExactlyInAnyOrder(true, false);
		assertThat(reservationCount(show)).isEqualTo(1);
		// and nobody else can take the seats afterwards
		assertThatThrownBy(() -> service.reserve(show, "bob", List.of("A1"), null))
			.isInstanceOf(SeatsUnavailableException.class);
	}

	private ResultActions reserve(UUID show, String user, String key, String seats) throws Exception {
		String keyField = key == null ? "" : ", \"idempotency_key\": \"" + key + "\"";
		return mvc.perform(post("/shows/{id}/reserve", show).header("Authorization", bearer(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"seats\": " + seats + keyField + "}"));
	}

	private UUID createShow(String... labels) throws Exception {
		String seats = "[\"" + String.join("\", \"", labels) + "\"]";
		String body = mvc.perform(post("/shows").header("Authorization", adminBearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Idem\", \"price_paise\": 100, \"seats\": " + seats + "}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	private long reservationCount(UUID show) {
		return jdbc.sql("SELECT count(*) FROM reservations WHERE show_id = ?").param(show).query(Long.class).single();
	}

	private String seatStatus(UUID show, String label) {
		return jdbc.sql("SELECT status FROM seats WHERE show_id = ? AND label = ?").params(show, label)
			.query(String.class).single();
	}

}
