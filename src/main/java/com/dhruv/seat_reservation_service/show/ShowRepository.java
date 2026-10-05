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

	/** One row per seat, show columns repeated. Empty list = no such show. */
	record SeatRow(UUID id, String name, long pricePaise, int totalSeats, int perUserLimit, String label,
			String status) {
	}

	/**
	 * Show and every seat in a single statement. In Postgres one statement reads one
	 * snapshot, so the per-seat statuses (and the counts derived from them) are consistent
	 * with each other even while reservations are committing. COLLATE "C" gives a stable
	 * byte-order sort regardless of the database's default collation.
	 */
	List<SeatRow> findWithSeats(UUID id) {
		return jdbc.sql("""
				SELECT s.id, s.name, s.price_paise, s.total_seats, s.per_user_limit, st.label, st.status
				FROM shows s
				LEFT JOIN seats st ON st.show_id = s.id
				WHERE s.id = :id
				ORDER BY st.label COLLATE "C"
				""")
			.param("id", id)
			.query(SeatRow.class)
			.list();
	}

	void insertShow(UUID id, String name, long pricePaise, int totalSeats, int perUserLimit) {
		jdbc.sql("""
				INSERT INTO shows (id, name, price_paise, total_seats, per_user_limit)
				VALUES (:id, :name, :pricePaise, :totalSeats, :perUserLimit)
				""")
			.param("id", id)
			.param("name", name)
			.param("pricePaise", pricePaise)
			.param("totalSeats", totalSeats)
			.param("perUserLimit", perUserLimit)
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
