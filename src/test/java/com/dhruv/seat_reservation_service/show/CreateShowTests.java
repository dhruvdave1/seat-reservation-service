package com.dhruv.seat_reservation_service.show;

import java.util.UUID;

import com.dhruv.seat_reservation_service.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CreateShowTests extends IntegrationTest {

	@Test
	void createsShowWithAllSeatsAvailable() throws Exception {
		MvcResult result = mvc.perform(post("/shows").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "  Coldplay  ", "price_paise": 250000, "seats": [" A1", "A2 ", "B1"]}
				"""))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", startsWith("/shows/")))
			.andExpect(jsonPath("$.name").value("Coldplay"))
			.andExpect(jsonPath("$.price_paise").value(250000))
			.andExpect(jsonPath("$.total_seats").value(3))
			.andExpect(jsonPath("$.counts.available").value(3))
			.andExpect(jsonPath("$.counts.held").value(0))
			.andExpect(jsonPath("$.counts.confirmed").value(0))
			.andExpect(jsonPath("$.seats[*].label").value(contains("A1", "A2", "B1")))
			.andExpect(jsonPath("$.seats[*].status").value(everyItem(is("available"))))
			.andReturn();

		UUID id = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
		assertThat(jdbc.sql("SELECT total_seats FROM shows WHERE id = ?").param(id).query(Integer.class).single())
			.isEqualTo(3);
		assertThat(jdbc.sql("""
				SELECT label FROM seats
				WHERE show_id = ? AND status = 'available' AND reservation_id IS NULL
				ORDER BY label
				""").param(id).query(String.class).list()).containsExactly("A1", "A2", "B1");
	}

	@Test
	void duplicateLabelsAfterTrimAreRejectedAndNothingIsStored() throws Exception {
		long before = showCount();
		mvc.perform(post("/shows").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Dup", "price_paise": 100, "seats": ["A1", " A1", "A2"]}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("Duplicate seat labels: [A1]"));
		assertThat(showCount()).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"name\": \"   \", \"price_paise\": 100, \"seats\": [\"A1\"]}",
			"{\"price_paise\": 100, \"seats\": [\"A1\"]}",
			"{\"name\": \"S\", \"price_paise\": 0, \"seats\": [\"A1\"]}",
			"{\"name\": \"S\", \"price_paise\": -5, \"seats\": [\"A1\"]}",
			"{\"name\": \"S\", \"price_paise\": 100.5, \"seats\": [\"A1\"]}",
			"{\"name\": \"S\", \"price_paise\": 1000000001, \"seats\": [\"A1\"]}",
			"{\"name\": \"S\", \"seats\": [\"A1\"]}",
			"{\"name\": \"S\", \"price_paise\": 100, \"seats\": []}",
			"{\"name\": \"S\", \"price_paise\": 100}",
			"{\"name\": \"S\", \"price_paise\": 100, \"seats\": [\"  \"]}",
			"{\"name\": \"S\", \"price_paise\": 100, \"seats\": [null]}",
			"{\"name\": \"S\", \"price_paise\": 100, \"seats\": [\"ABCDEFGHIJKLMNOPQ\"]}",
			"{\"name\": \"S\", \"price_paise\": 100, \"seats\": [\"A1\"",
			"not json" })
	void invalidInputIsA400ProblemDetail(String body) throws Exception {
		long before = showCount();
		mvc.perform(post("/shows").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(header().string("Content-Type", startsWith("application/problem+json")));
		assertThat(showCount()).isEqualTo(before);
	}

	private long showCount() {
		return jdbc.sql("SELECT count(*) FROM shows").query(Long.class).single();
	}

}
