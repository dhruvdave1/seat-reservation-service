package com.dhruv.seat_reservation_service;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ObservabilityTests extends IntegrationTest {

	@Test
	void requestIdIsEchoedOrGenerated() throws Exception {
		mvc.perform(get("/actuator/health/liveness").header("X-Request-Id", "abc-123"))
			.andExpect(header().string("X-Request-Id", "abc-123"));
		mvc.perform(get("/actuator/health/liveness").header("X-Request-Id", "bad id with spaces"))
			.andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
	}

	/** Counters move with API outcomes, and the seat gauges match the database. */
	@Test
	void metricsReconcileWithApiOutcomes() throws Exception {
		String body = mvc.perform(post("/shows").header("Authorization", adminBearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Metrics\", \"price_paise\": 100, \"seats\": [\"A1\", \"A2\"]}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID show = UUID.fromString(JsonPath.read(body, "$.id"));

		double confirmed = metric("reservations_confirmed_total");
		double taken = metric("reservations_declined_total{application=\"seat-reservation-service\",reason=\"seat_taken\"}");
		double replays = metric("reservations_declined_total{application=\"seat-reservation-service\",reason=\"idempotent_replay\"}");

		for (String user : List.of("alice", "bob")) {
			mvc.perform(post("/shows/{id}/reserve", show).header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"seats\": [\"A1\"], \"idempotency_key\": \"m-1\"}"));
		}
		mvc.perform(post("/shows/{id}/reserve", show).header("Authorization", bearer("alice"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"seats\": [\"A1\"], \"idempotency_key\": \"m-1\"}")).andExpect(status().isCreated());

		assertThat(metric("reservations_confirmed_total")).isEqualTo(confirmed + 1);
		assertThat(metric("reservations_declined_total{application=\"seat-reservation-service\",reason=\"seat_taken\"}"))
			.isEqualTo(taken + 1);
		assertThat(metric("reservations_declined_total{application=\"seat-reservation-service\",reason=\"idempotent_replay\"}"))
			.isEqualTo(replays + 1);

		Thread.sleep(1100); // gauge snapshot is cached for 1 s
		long dbAvailable = jdbc.sql("SELECT count(*) FROM seats WHERE status = 'available'").query(Long.class).single();
		long dbConfirmed = jdbc.sql("SELECT count(*) FROM seats WHERE status = 'confirmed'").query(Long.class).single();
		String scrape = scrape();
		assertThat(gauge(scrape, "seats_available")).isEqualTo(dbAvailable);
		assertThat(gauge(scrape, "seats_confirmed")).isEqualTo(dbConfirmed);
	}

	private String scrape() throws Exception {
		return mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
	}

	private double metric(String series) throws Exception {
		String scrape = scrape();
		String name = series.contains("{") ? series : series + "{application=\"seat-reservation-service\"}";
		Matcher m = Pattern.compile("^" + Pattern.quote(name) + " (\\S+)$", Pattern.MULTILINE).matcher(scrape);
		assertThat(m.find()).as("series %s in scrape", name).isTrue();
		return Double.parseDouble(m.group(1));
	}

	private static long gauge(String scrape, String name) {
		Matcher m = Pattern.compile("^" + name + "\\{[^}]*\\} (\\S+)$", Pattern.MULTILINE).matcher(scrape);
		assertThat(m.find()).as("gauge %s in scrape", name).isTrue();
		return (long) Double.parseDouble(m.group(1));
	}

}
