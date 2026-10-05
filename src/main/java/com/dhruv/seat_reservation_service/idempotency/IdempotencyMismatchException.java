package com.dhruv.seat_reservation_service.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 409: the idempotency key was already used by this user for a different request. */
public class IdempotencyMismatchException extends ErrorResponseException {

	public IdempotencyMismatchException() {
		super(HttpStatus.CONFLICT, problem(), null);
	}

	private static ProblemDetail problem() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"Idempotency key was already used with a different request");
		problem.setProperty("reason", "idempotency_mismatch");
		return problem;
	}

}
