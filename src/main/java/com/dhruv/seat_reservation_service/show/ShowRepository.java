package com.dhruv.seat_reservation_service.show;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ShowRepository {

	private final JdbcClient jdbc;

	ShowRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insertShow(UUID id, String name, long pricePaise, int totalSeats) {
		jdbc.sql("""
				INSERT INTO shows (id, name, price_paise, total_seats)
				VALUES (:id, :name, :pricePaise, :totalSeats)
				""")
			.param("id", id)
			.param("name", name)
			.param("pricePaise", pricePaise)
			.param("totalSeats", totalSeats)
			.update();
	}

	/**
	 * All seats in one statement and one round trip: unnest turns the label array into
	 * rows. Every seat starts 'available' with no reservation (column defaults).
	 */
	int insertSeats(UUID showId, List<String> labels) {
		return jdbc.sql("""
				INSERT INTO seats (show_id, label)
				SELECT :showId, unnest(CAST(:labels AS text[]))
				""")
			.param("showId", showId)
			.param("labels", labels.toArray(String[]::new))
			.update();
	}

}
