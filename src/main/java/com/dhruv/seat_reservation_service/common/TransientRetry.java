package com.dhruv.seat_reservation_service.common;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

/**
 * Re-runs a whole transaction when Postgres aborted it for a concurrency reason that a
 * retry can fix: deadlock (40P01), serialization failure (40001), lock not available.
 * Spring translates all of these to {@link ConcurrencyFailureException}.
 *
 * <p>Call it from outside the @Transactional method, so every attempt is a fresh
 * transaction. Bounded: after the last attempt the error propagates as a real 500, it is
 * never disguised as a 409. Pool-exhaustion timeouts are deliberately not retried; under
 * overload, retrying adds load.
 */
@Component
public class TransientRetry {

	private static final Logger log = LoggerFactory.getLogger(TransientRetry.class);

	static final int MAX_ATTEMPTS = 3;

	private final Counter retries;

	public TransientRetry(MeterRegistry registry) {
		this.retries = Counter.builder("transaction.retries")
			.description("Transactions re-run after a deadlock or serialization failure")
			.register(registry);
	}

	public <T> T run(Supplier<T> transaction) {
		for (int attempt = 1;; attempt++) {
			try {
				return transaction.get();
			}
			catch (ConcurrencyFailureException ex) {
				if (attempt == MAX_ATTEMPTS) {
					throw ex;
				}
				retries.increment();
				log.atWarn()
					.addKeyValue("attempt", attempt)
					.addKeyValue("error", ex.getClass().getSimpleName())
					.log("transient database conflict, retrying transaction");
				backoff(attempt);
			}
		}
	}

	/** 5-25 ms, then 10-50 ms: short, jittered so retried transactions do not collide again. */
	private static void backoff(int attempt) {
		long base = 5L * attempt;
		try {
			Thread.sleep(base + ThreadLocalRandom.current().nextLong(base * 4 + 1));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while backing off", ex);
		}
	}

}
