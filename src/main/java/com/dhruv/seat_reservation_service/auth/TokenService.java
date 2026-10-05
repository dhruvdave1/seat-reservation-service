package com.dhruv.seat_reservation_service.auth;

import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

	private final JwtEncoder encoder;

	private final AuthProperties props;

	TokenService(JwtEncoder encoder, AuthProperties props) {
		this.encoder = encoder;
		this.props = props;
	}

	public TokenResponse issue(String userId, boolean admin) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(SecurityConfig.ISSUER)
			.subject(userId)
			.issuedAt(now)
			.expiresAt(now.plus(props.tokenTtl()))
			.claim("roles", admin ? List.of("ADMIN", "USER") : List.of("USER"))
			.build();
		String token = encoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
		return new TokenResponse(token, "Bearer", props.tokenTtl().toSeconds());
	}

	public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

}
