package com.dhruv.seat_reservation_service.reservation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 409: this reservation would take the user past the show's per-user seat limit. */
public class PerUserLimitException extends ErrorResponseException {

	public PerUserLimitException(int requested) {
		super(HttpStatus.CONFLICT, problem(requested), null);
	}

	private static ProblemDetail problem(int requested) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"Reserving " + requested + " more seat(s) would exceed the per-user limit for this show");
		problem.setProperty("reason", "per_user_limit");
		return problem;
	}

}
