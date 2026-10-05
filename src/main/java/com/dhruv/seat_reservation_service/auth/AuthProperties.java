package com.dhruv.seat_reservation_service.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Secrets come from the environment (JWT_SECRET, ADMIN_KEY); there are no defaults, so a
 * missing secret stops startup instead of running with a guessable key.
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(String jwtSecret, Duration tokenTtl, String adminKey) {

	public AuthProperties {
		if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalArgumentException("app.auth.jwt-secret must be at least 32 bytes for HS256");
		}
		if (adminKey == null || adminKey.isBlank()) {
			throw new IllegalArgumentException("app.auth.admin-key must be set");
		}
		if (tokenTtl == null) {
			tokenTtl = Duration.ofHours(1);
		}
	}

}
