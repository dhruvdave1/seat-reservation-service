package com.dhruv.seat_reservation_service.show;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.dhruv.seat_reservation_service.common.NotFoundException;
import com.dhruv.seat_reservation_service.common.SeatLabels;
import com.dhruv.seat_reservation_service.show.ShowResponse.SeatCounts;
import com.dhruv.seat_reservation_service.show.ShowResponse.SeatView;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

	private final ShowRepository shows;

	ShowService(ShowRepository shows) {
		this.shows = shows;
	}

	/** Show and all its seats commit together or not at all. */
	@Transactional
	public ShowResponse create(CreateShowRequest request) {
		String name = request.name().strip();
		List<String> labels = SeatLabels.normalize(request.seats());
		int perUserLimit = request.perUserLimit() != null ? request.perUserLimit()
				: CreateShowRequest.DEFAULT_PER_USER_LIMIT;
		UUID id = UUID.randomUUID();

		shows.insertShow(id, name, request.pricePaise(), labels.size(), perUserLimit);
		shows.insertSeats(id, labels);

		List<SeatView> seats = labels.stream().map(label -> new SeatView(label, "available")).toList();
		return new ShowResponse(id, name, request.pricePaise(), labels.size(), perUserLimit,
				new SeatCounts(labels.size(), 0, 0), seats);
	}

	/** Counts are derived from the same rows as the seat list, so they always agree. */
	public ShowResponse get(UUID id) {
		List<ShowRepository.SeatRow> rows = shows.findWithSeats(id);
		if (rows.isEmpty()) {
			throw new NotFoundException("Show " + id + " not found");
		}
		int available = 0;
		int held = 0;
		int confirmed = 0;
		List<SeatView> seats = new ArrayList<>(rows.size());
		for (ShowRepository.SeatRow row : rows) {
			if (row.label() == null) {
				continue; // LEFT JOIN on a show with no seats; V1 forbids that, but stay safe
			}
			switch (row.status()) {
				case "available" -> available++;
				case "held" -> held++;
				case "confirmed" -> confirmed++;
				default -> throw new IllegalStateException("Unknown seat status: " + row.status());
			}
			seats.add(new SeatView(row.label(), row.status()));
		}
		ShowRepository.SeatRow show = rows.getFirst();
		return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.totalSeats(), show.perUserLimit(),
				new SeatCounts(available, held, confirmed), seats);
	}

}
