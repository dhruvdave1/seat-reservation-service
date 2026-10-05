package com.dhruv.seat_reservation_service.show;

import java.util.UUID;

import com.dhruv.seat_reservation_service.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GetShowTests extends IntegrationTest {

	@Test
	void returnsSeatsSortedWithCountsAndNeedsNoToken() throws Exception {
		UUID id = createShow("[\"B1\", \"A2\", \"A10\"]");

		mvc.perform(get("/shows/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.name").value("Get"))
			.andExpect(jsonPath("$.price_paise").value(700))
			.andExpect(jsonPath("$.total_seats").value(3))
			.andExpect(jsonPath("$.counts.available").value(3))
			.andExpect(jsonPath("$.counts.held").value(0))
			.andExpect(jsonPath("$.counts.confirmed").value(0))
			// byte order (COLLATE "C"): "A10" < "A2"
			.andExpect(jsonPath("$.seats[*].label").value(contains("A10", "A2", "B1")));
	}

	@Test
	void countsReflectConfirmedSeats() throws Exception {
		UUID id = createShow("[\"A1\", \"A2\", \"A3\"]");
		UUID reservation = UUID.randomUUID();
		jdbc.sql("INSERT INTO reservations (id, user_id, show_id, amount_paise) VALUES (?, 'u1', ?, 1400)")
			.params(reservation, id)
			.update();
		jdbc.sql("""
				UPDATE seats SET status = 'confirmed', reservation_id = ?
				WHERE show_id = ? AND label IN ('A1', 'A3')
				""").params(reservation, id).update();

		mvc.perform(get("/shows/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.counts.available").value(1))
			.andExpect(jsonPath("$.counts.confirmed").value(2))
			.andExpect(jsonPath("$.seats[*].status").value(contains("confirmed", "available", "confirmed")));
	}

	@Test
	void unknownShowIs404() throws Exception {
		mvc.perform(get("/shows/{id}", UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(header().string("Content-Type", startsWith("application/problem+json")));
	}

	@Test
	void malformedIdIs400() throws Exception {
		mvc.perform(get("/shows/not-a-uuid")).andExpect(status().isBadRequest());
	}

	private UUID createShow(String seats) throws Exception {
		String body = mvc.perform(post("/shows").header("Authorization", adminBearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Get\", \"price_paise\": 700, \"seats\": " + seats + "}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

}
