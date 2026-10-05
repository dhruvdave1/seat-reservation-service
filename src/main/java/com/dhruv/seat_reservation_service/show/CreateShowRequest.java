package com.dhruv.seat_reservation_service.show;

import java.util.List;

import jakarta.validation.constraints.Max;
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
		@NotNull @Positive @Max(CreateShowRequest.MAX_PRICE_PAISE) Long pricePaise,
		@NotEmpty @Size(max = CreateShowRequest.MAX_SEATS) List<@NotBlank @Size(max = 16) String> seats) {

	public static final int MAX_SEATS = 10_000;

	/**
	 * Rs 1 crore per seat. Keeps price * seat count far inside bigint, so the reserve
	 * INSERT can never fail with "bigint out of range" (a 500).
	 */
	public static final long MAX_PRICE_PAISE = 1_000_000_000L;

}
