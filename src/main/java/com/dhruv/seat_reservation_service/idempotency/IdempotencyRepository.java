package com.dhruv.seat_reservation_service.idempotency;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

	private final JdbcClient jdbc;

	IdempotencyRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public record StoredKey(String requestHash, String response) {
	}

	/**
	 * Claims the key for this transaction. Returns true if this call inserted it. If another
	 * transaction inserted the same key and has not finished, this blocks on the primary key
	 * until it commits (then returns false) or rolls back (then inserts and returns true).
	 */
	public boolean claim(String userId, String key, String requestHash) {
		return jdbc.sql("""
				INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash)
				VALUES (:userId, :key, :requestHash)
				ON CONFLICT (user_id, idempotency_key) DO NOTHING
				""")
			.param("userId", userId)
			.param("key", key)
			.param("requestHash", requestHash)
			.update() == 1;
	}

	/** The committed key that beat us to the claim. */
	public StoredKey find(String userId, String key) {
		return jdbc.sql("""
				SELECT request_hash, response
				FROM idempotency_keys
				WHERE user_id = :userId AND idempotency_key = :key
				""")
			.param("userId", userId)
			.param("key", key)
			.query(StoredKey.class)
			.single();
	}

	/** Records the result on the claimed key, in the same transaction as the reservation. */
	public void complete(String userId, String key, UUID reservationId, String response) {
		jdbc.sql("""
				UPDATE idempotency_keys
				SET reservation_id = :reservationId, response = :response
				WHERE user_id = :userId AND idempotency_key = :key
				""")
			.param("reservationId", reservationId)
			.param("response", response)
			.param("userId", userId)
			.param("key", key)
			.update();
	}

}
