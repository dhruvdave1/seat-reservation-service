package com.dhruv.seat_reservation_service.common;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One rule for seat labels everywhere: shows are created and reserved with the same normalization. */
public final class SeatLabels {

	private SeatLabels() {
	}

	/**
	 * Strips surrounding whitespace and rejects duplicates (400), so " A1" and "A1" are the
	 * same seat and ["A1", "A1"] never reaches SQL as a false "seat taken".
	 */
	public static List<String> normalize(List<String> raw) {
		List<String> labels = new ArrayList<>(raw.size());
		Set<String> seen = new HashSet<>();
		Set<String> duplicates = new LinkedHashSet<>();
		for (String label : raw) {
			String stripped = label.strip();
			if (!seen.add(stripped)) {
				duplicates.add(stripped);
			}
			labels.add(stripped);
		}
		if (!duplicates.isEmpty()) {
			throw new InvalidRequestException("Duplicate seat labels: " + duplicates);
		}
		return labels;
	}

}
