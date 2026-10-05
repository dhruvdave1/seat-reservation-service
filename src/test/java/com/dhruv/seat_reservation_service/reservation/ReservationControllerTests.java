package com.dhruv.seat_reservation_service.reservation;

import java.util.List;
import java.util.UUID;

import com.dhruv.seat_reservation_service.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of POST /shows/{id}/reserve, with the service mocked: auth, validation,
 * label normalization and token-derived identity. The reserve transaction itself is tested
 * against the real service.
 */
class ReservationControllerTests extends IntegrationTest {

	@MockitoBean
	ReservationService service;

	private final UUID showId = UUID.randomUUID();

	@Test
	void passesTokenSubjectAndNormalizedLabelsAndReturns201() throws Exception {
		given(service.reserve(eq(showId), eq("alice"), eq(List.of("A1", "A2")), isNull()))
			.willReturn(new ReservationService.ReserveResult(new ReservationResponse(UUID.randomUUID(), showId, "alice", List.of("A1", "A2"), 200, "confirmed"), false));

		reserve(bearer("alice"), "{\"seats\": [\" A1\", \"A2 \"]}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.reservation_id").exists())
			.andExpect(jsonPath("$.show_id").value(showId.toString()))
			.andExpect(jsonPath("$.user_id").value("alice"))
			.andExpect(jsonPath("$.amount_paise").value(200))
			.andExpect(jsonPath("$.status").value("confirmed"));
	}

	@Test
	void userIdInTheBodyIsIgnored() throws Exception {
		given(service.reserve(any(), anyString(), anyList(), any()))
			.willReturn(new ReservationService.ReserveResult(new ReservationResponse(UUID.randomUUID(), showId, "alice", List.of("A1"), 100, "confirmed"), false));

		reserve(bearer("alice"), "{\"seats\": [\"A1\"], \"user_id\": \"mallory\"}").andExpect(status().isCreated());

		verify(service).reserve(showId, "alice", List.of("A1"), null);
	}

	@Test
	void serviceConflictIsA409WithReason() throws Exception {
		given(service.reserve(any(), anyString(), anyList(), any())).willThrow(new SeatsUnavailableException(List.of("A1")));

		reserve(bearer("alice"), "{\"seats\": [\"A1\"]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("seat_taken"));
	}

	@Test
	void noTokenIs401() throws Exception {
		reserve(null, "{\"seats\": [\"A1\"]}").andExpect(status().isUnauthorized());
		verify(service, never()).reserve(any(), anyString(), anyList(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"seats\": []}", "{\"seats\": [\"  \"]}", "{\"seats\": [null]}",
			"{\"seats\": [\"ABCDEFGHIJKLMNOPQ\"]}", "{\"seats\": [\"A1\", \" A1\"]}", "not json" })
	void invalidBodyIs400AndNeverReachesTheService(String body) throws Exception {
		reserve(bearer("alice"), body).andExpect(status().isBadRequest());
		verify(service, never()).reserve(any(), anyString(), anyList(), any());
	}

	@Test
	void malformedShowIdIs400() throws Exception {
		mvc.perform(post("/shows/not-a-uuid/reserve").header("Authorization", bearer("alice"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"seats\": [\"A1\"]}")).andExpect(status().isBadRequest());
	}

	private ResultActions reserve(String authorization, String body) throws Exception {
		var request = post("/shows/{id}/reserve", showId).contentType(MediaType.APPLICATION_JSON).content(body);
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return mvc.perform(request);
	}

}
