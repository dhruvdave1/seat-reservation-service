package com.dhruv.seat_reservation_service.show;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /shows. Limits mirror the CHECK constraints in V1 so bad input is a 400
 * here, never a constraint violation (500) in the database.
 */
public record CreateShowRequest(
		@NotBlank @Size(max = 200) String name,
		@NotNull @Positive Long pricePaise,
		@NotEmpty @Size(max = CreateShowRequest.MAX_SEATS) List<@NotBlank @Size(max = 16) String> seats) {

	public static final int MAX_SEATS = 10_000;

}
