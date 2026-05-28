package com.notification.worker;

import com.notification.config.NotificationProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 지수 백오프 + jitter 재시도 정책. delay = min(initial * multiplier^(retryNumber-1), maxBackoff) + jitter.
 * jitter는 thundering herd 방지를 위한 0~jitterMax 랜덤.
 */
public class RetryPolicy {

	private final NotificationProperties.Retry retry;

	public RetryPolicy(NotificationProperties.Retry retry) {
		this.retry = retry;
	}

	/** retryNumber회차(1부터) 재시도가 발생할 시각. */
	public Instant nextRetryAt(int retryNumber, Instant now) {
		double exponentialMillis =
				retry.initialBackoff().toMillis() * Math.pow(retry.multiplier(), retryNumber - 1);
		long cappedMillis = Math.min((long) exponentialMillis, retry.maxBackoff().toMillis());
		long jitterMillis = retry.jitterMax().isZero()
				? 0
				: ThreadLocalRandom.current().nextLong(retry.jitterMax().toMillis() + 1);
		return now.plus(Duration.ofMillis(cappedMillis + jitterMillis));
	}

	/** 지금까지의 시도 횟수가 최대치에 도달했는지. */
	public boolean hasReachedMaxAttempts(int attemptsMade) {
		return attemptsMade >= retry.maxAttempts();
	}
}
