package com.dhruv.seat_reservation_service.reservation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 400: the show exists but some requested labels are not seats of it. Rolls back like any RuntimeException. */
public class UnknownSeatsException extends ErrorResponseException {

	public UnknownSeatsException(List<String> unknown) {
		super(HttpStatus.BAD_REQUEST, problem(unknown), null);
	}

	private static ProblemDetail problem(List<String> unknown) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Unknown seats for this show: " + unknown);
		problem.setProperty("reason", "unknown_seat");
		return problem;
	}

}
