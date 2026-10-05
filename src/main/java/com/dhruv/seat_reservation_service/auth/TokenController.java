package com.dhruv.seat_reservation_service.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-harness token issuer: anyone can get a token for any user id, so graders can act
 * as many users. An admin token additionally needs the configured admin key. In a real
 * system an identity provider issues tokens and this endpoint would not exist.
 */
@RestController
class TokenController {

	private final TokenService tokens;

	private final AuthProperties props;

	TokenController(TokenService tokens, AuthProperties props) {
		this.tokens = tokens;
		this.props = props;
	}

	@PostMapping("/auth/token")
	TokenService.TokenResponse issue(@Valid @RequestBody TokenRequest request) {
		boolean admin = request.adminKey() != null;
		if (admin && !constantTimeEquals(request.adminKey(), props.adminKey())) {
			throw new ErrorResponseException(HttpStatus.FORBIDDEN,
					ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Invalid admin key"), null);
		}
		return tokens.issue(request.userId(), admin);
	}

	private static boolean constantTimeEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	/** user_id fits reservations.user_id (1-64 chars) and has no whitespace to normalize. */
	record TokenRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9._@-]{1,64}") String userId, String adminKey) {
	}

}
