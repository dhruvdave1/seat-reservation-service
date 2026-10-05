package com.dhruv.seat_reservation_service.show;

import java.util.List;
import java.util.UUID;

public record ShowResponse(UUID id, String name, long pricePaise, int totalSeats, int perUserLimit, SeatCounts counts,
		List<SeatView> seats) {

	public record SeatCounts(int available, int held, int confirmed) {
	}

	public record SeatView(String label, String status) {
	}

}
