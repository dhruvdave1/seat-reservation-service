package com.dhruv.seat_reservation_service.common;

import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransientRetryTests {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final TransientRetry retry = new TransientRetry(registry);

	@Test
	void retriesConcurrencyFailuresThenSucceeds() {
		AtomicInteger calls = new AtomicInteger();
		String result = retry.run(() -> {
			if (calls.incrementAndGet() < 3) {
				throw new PessimisticLockingFailureException("deadlock detected (40P01)");
			}
			return "ok";
		});
		assertThat(result).isEqualTo("ok");
		assertThat(calls).hasValue(3);
		assertThat(registry.counter("transaction.retries").count()).isEqualTo(2);
	}

	@Test
	void givesUpAfterMaxAttemptsAndRethrows() {
		AtomicInteger calls = new AtomicInteger();
		assertThatThrownBy(() -> retry.run(() -> {
			calls.incrementAndGet();
			throw new CannotAcquireLockException("still locked");
		})).isInstanceOf(CannotAcquireLockException.class);
		assertThat(calls).hasValue(TransientRetry.MAX_ATTEMPTS);
	}

	@Test
	void doesNotRetryOtherFailures() {
		AtomicInteger calls = new AtomicInteger();
		assertThatThrownBy(() -> retry.run(() -> {
			calls.incrementAndGet();
			throw new DataIntegrityViolationException("check constraint");
		})).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(calls).hasValue(1);
	}

}
