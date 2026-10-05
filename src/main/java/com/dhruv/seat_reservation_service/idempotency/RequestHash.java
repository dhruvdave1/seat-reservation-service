package com.dhruv.seat_reservation_service.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Identity of a reserve request for idempotency: show id plus the sorted, normalized seat
 * labels. ["A2","A1"] and ["A1","A2"] are the same request; another show or seat set is not.
 */
public final class RequestHash {

	private RequestHash() {
	}

	public static String of(UUID showId, List<String> labels) {
		String canonical = showId + "\n" + String.join("\n", labels.stream().sorted().toList());
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by every JVM", ex);
		}
	}

}
