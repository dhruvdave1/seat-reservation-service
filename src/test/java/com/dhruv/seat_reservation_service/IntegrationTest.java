package com.dhruv.seat_reservation_service;

import com.dhruv.seat_reservation_service.auth.TokenService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared setup so every integration test reuses one Spring context and one Postgres
 * container, with security filters on.
 */
// Pool of 50 so concurrency tests really overlap transactions instead of queueing on 10 connections.
@SpringBootTest(properties = { "app.auth.jwt-secret=" + IntegrationTest.JWT_SECRET,
		"app.auth.admin-key=" + IntegrationTest.ADMIN_KEY, "spring.datasource.hikari.maximum-pool-size=50" })
@AutoConfigureMockMvc
// Metrics export is off in tests by default; on here so the Prometheus scrape can be asserted.
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

	protected static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-000";

	protected static final String ADMIN_KEY = "test-admin-key";

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected JdbcClient jdbc;

	@Autowired
	protected TokenService tokens;

	protected String bearer(String userId) {
		return "Bearer " + tokens.issue(userId, false).accessToken();
	}

	protected String adminBearer() {
		return "Bearer " + tokens.issue("admin", true).accessToken();
	}

}
