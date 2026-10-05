package com.dhruv.seat_reservation_service.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Request is well-formed JSON but semantically invalid. Rendered as an RFC 9457 400. */
public class InvalidRequestException extends ErrorResponseException {

	public InvalidRequestException(String detail) {
		super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
	}

}
