package com.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.config.NotificationProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
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

	@Test
	@DisplayName("jitter: 동일 회차 반복 호출에도 값을 분산시킨다 (thundering herd 방지)")
	void jitter_spreadsValues() {
		RetryPolicy p = policy(); // jitterMax 30s
		Set<Instant> results = new HashSet<>();
		IntStream.range(0, 50).forEach(i -> results.add(p.nextRetryAt(1, NOW)));

		// jitter가 있으면 같은 base(1분)여도 여러 값으로 흩어진다 (상수 0/max였다면 1개만)
		assertThat(results).hasSizeGreaterThan(5);
		// 그리고 모두 [base, base+jitterMax] = [+60s, +90s] 범위 내
		results.forEach(r -> assertThat(r).isBetween(NOW.plusSeconds(60), NOW.plusSeconds(90)));
	}

	@Test
	@DisplayName("jitterMax=0이면 jitter 없이 결정적(분산 없음)")
	void zeroJitter_deterministic() {
		RetryPolicy p = new RetryPolicy(new NotificationProperties.Retry(
				5, Duration.ofMinutes(1), 2.0, Duration.ofMinutes(16), Duration.ZERO));
		Set<Instant> results = new HashSet<>();
		IntStream.range(0, 20).forEach(i -> results.add(p.nextRetryAt(1, NOW)));

		assertThat(results).hasSize(1);                       // 모두 동일
		assertThat(results).containsExactly(NOW.plusSeconds(60)); // 정확히 base
	}
}
