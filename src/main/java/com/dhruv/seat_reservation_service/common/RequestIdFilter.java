package com.dhruv.seat_reservation_service.common;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlation id for every request: taken from X-Request-Id when the caller sends a sane
 * one, generated otherwise. It is put in the MDC (so every JSON log line carries
 * request_id), echoed in the response header, and closes with one access log line.
 * Runs first, so even security rejections are logged with an id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";

	static final String MDC_KEY = "request_id";

	private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private static final Logger access = LoggerFactory.getLogger("access");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String incoming = request.getHeader(HEADER);
		String requestId = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
		long start = System.nanoTime();
		MDC.put(MDC_KEY, requestId);
		response.setHeader(HEADER, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			access.atInfo()
				.addKeyValue("method", request.getMethod())
				.addKeyValue("path", request.getRequestURI())
				.addKeyValue("status", response.getStatus())
				.addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000)
				.log("request completed");
			MDC.remove(MDC_KEY);
		}
	}

}
