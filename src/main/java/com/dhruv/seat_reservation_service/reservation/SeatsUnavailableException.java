package com.dhruv.seat_reservation_service.reservation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 409: at least one requested seat is not available. It is a RuntimeException, so throwing
 * it inside a @Transactional method rolls the whole reservation back. The "reason" property
 * is what the burst script and the declined-by-reason metric will group on.
 */
public class SeatsUnavailableException extends ErrorResponseException {

	public SeatsUnavailableException(List<String> requested) {
		super(HttpStatus.CONFLICT, problem(requested), null);
	}

	private static ProblemDetail problem(List<String> requested) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"One or more seats are not available: " + requested);
		problem.setProperty("reason", "seat_taken");
		return problem;
	}

}
