package com.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** notification.* 설정 바인딩. 모든 운영 파라미터는 yml에서 조정 가능(ADR 00-DECISIONS 참고). */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
		Polling polling,
		Retry retry,
		Sender sender,
		Sweeper sweeper,
		Worker worker,
		Retention retention,
		MockSender mockSender,
		boolean schedulingEnabled) {

	public record Polling(Duration interval, int batchSize) {
	}

	public record Retry(
			int maxAttempts,
			Duration initialBackoff,
			double multiplier,
			Duration maxBackoff,
			Duration jitterMax) {
	}

	public record Sender(Duration timeout) {
	}

	public record Sweeper(Duration interval, Duration leaseTimeout) {
	}

	/**
	 * 인스턴스당 발송 동시성(고정 스레드풀 크기). 외부 자원 한도에 맞춰 의도적으로 제한한다.
	 * 불변식: lease-timeout ≥ ⌈batch-size/concurrency⌉ × sender-timeout + 마진 (좀비 오인 방지).
	 */
	public record Worker(int concurrency) {
	}

	public record Retention(boolean enabled, int sentDays, int deadLetterDays) {
	}

	/** Mock sender 동작 제어. 기본 실패율 0(운영/테스트), 실패는 recipientId 패턴으로 결정적 유발. */
	public record MockSender(double failureRate) {
	}
}
