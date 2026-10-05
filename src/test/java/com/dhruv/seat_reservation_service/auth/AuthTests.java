package com.dhruv.seat_reservation_service.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import com.dhruv.seat_reservation_service.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthTests extends IntegrationTest {

	private static final String SHOW = """
			{"name": "Auth", "price_paise": 100, "seats": ["A1"]}
			""";

	@Autowired
	JwtDecoder decoder;

	@Autowired
	JwtEncoder encoder;

	@Test
	void userTokenCarriesSubjectAndUserRoleOnly() throws Exception {
		String body = token("{\"user_id\": \"alice\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.token_type").value("Bearer"))
			.andExpect(jsonPath("$.expires_in").value(3600))
			.andReturn().getResponse().getContentAsString();
		Jwt jwt = decoder.decode(JsonPath.read(body, "$.access_token"));
		assertThat(jwt.getSubject()).isEqualTo("alice");
		assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
	}

	@Test
	void adminTokenNeedsTheAdminKey() throws Exception {
		token("{\"user_id\": \"ops\", \"admin_key\": \"" + ADMIN_KEY + "\"}").andExpect(status().isOk());
		token("{\"user_id\": \"ops\", \"admin_key\": \"wrong\"}").andExpect(status().isForbidden());
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"user_id\": \"\"}", "{\"user_id\": \" alice\"}",
			"{\"user_id\": \"a b\"}", "{\"user_id\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}" })
	void invalidUserIdIsA400(String body) throws Exception {
		token(body).andExpect(status().isBadRequest());
	}

	@Test
	void createShowRequiresAdmin() throws Exception {
		createShow(null).andExpect(status().isUnauthorized());
		createShow(bearer("alice")).andExpect(status().isForbidden());
		createShow(adminBearer()).andExpect(status().isCreated());
	}

	@Test
	void expiredTokenIsRejected() throws Exception {
		Instant past = Instant.now().minusSeconds(3600);
		createShow("Bearer " + sign(encoder, "admin", past.minusSeconds(60), past)).andExpect(status().isUnauthorized());
	}

	@Test
	void tokenSignedWithAnotherKeyIsRejected() throws Exception {
		JwtEncoder forger = NimbusJwtEncoder
			.withSecretKey(new SecretKeySpec("some-other-secret-at-least-32-bytes!!".getBytes(StandardCharsets.UTF_8),
					"HmacSHA256"))
			.build();
		Instant now = Instant.now();
		createShow("Bearer " + sign(forger, "admin", now, now.plusSeconds(600))).andExpect(status().isUnauthorized());
	}

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
		mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
	}

	private ResultActions token(String body) throws Exception {
		return mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions createShow(String authorization) throws Exception {
		var request = post("/shows").contentType(MediaType.APPLICATION_JSON).content(SHOW);
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return mvc.perform(request);
	}

	private static String sign(JwtEncoder encoder, String subject, Instant issuedAt, Instant expiresAt) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(SecurityConfig.ISSUER)
			.subject(subject)
			.issuedAt(issuedAt)
			.expiresAt(expiresAt)
			.claim("roles", List.of("ADMIN"))
			.build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

}
