package com.dhruv.seat_reservation_service.show;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
class ShowController {

	private final ShowService service;

	ShowController(ShowService service) {
		this.service = service;
	}

	@PostMapping
	ResponseEntity<ShowResponse> create(@Valid @RequestBody CreateShowRequest request) {
		ShowResponse show = service.create(request);
		return ResponseEntity.created(URI.create("/shows/" + show.id())).body(show);
	}

}
