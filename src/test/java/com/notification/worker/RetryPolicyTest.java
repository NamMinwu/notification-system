package com.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.config.NotificationProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

	private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");

	private RetryPolicy policy() {
		return new RetryPolicy(new NotificationProperties.Retry(
				5, Duration.ofMinutes(1), 2.0, Duration.ofMinutes(16), Duration.ofSeconds(30)));
	}

	private void assertDelayWithin(int retryNumber, Duration base, Duration jitterMax) {
		Instant next = policy().nextRetryAt(retryNumber, NOW);
		Instant lower = NOW.plus(base);
		Instant upper = NOW.plus(base).plus(jitterMax);
		assertThat(next).isBetween(lower, upper);
	}

	@Test
	@DisplayName("지수 백오프: 1→2→4→8→16분, 각 단계 0~30초 jitter 범위")
	void exponentialBackoffWithJitter() {
		assertDelayWithin(1, Duration.ofMinutes(1), Duration.ofSeconds(30));
		assertDelayWithin(2, Duration.ofMinutes(2), Duration.ofSeconds(30));
		assertDelayWithin(3, Duration.ofMinutes(4), Duration.ofSeconds(30));
		assertDelayWithin(4, Duration.ofMinutes(8), Duration.ofSeconds(30));
		assertDelayWithin(5, Duration.ofMinutes(16), Duration.ofSeconds(30));
	}

	@Test
	@DisplayName("maxBackoff(16분)로 상한 — 6회차도 16분으로 캡")
	void capsAtMaxBackoff() {
		assertDelayWithin(6, Duration.ofMinutes(16), Duration.ofSeconds(30));
		assertDelayWithin(10, Duration.ofMinutes(16), Duration.ofSeconds(30));
	}

	@Test
	@DisplayName("hasReachedMaxAttempts: 시도 횟수가 max 이상이면 true")
	void maxAttempts() {
		RetryPolicy p = policy();
		assertThat(p.hasReachedMaxAttempts(4)).isFalse();
		assertThat(p.hasReachedMaxAttempts(5)).isTrue();
		assertThat(p.hasReachedMaxAttempts(6)).isTrue();
	}
}
