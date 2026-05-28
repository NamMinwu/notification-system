package com.notification.sender;

import com.notification.domain.Notification;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock 발송 공통 로직. 실제 발송 대신 실패를 시뮬레이션한다.
 * recipientId 접두사로 결정적 실패를 유발하고(테스트용), 설정된 failureRate로 무작위 일시 실패도 낸다.
 */
public abstract class AbstractMockSender implements NotificationSender {

	private static final String TRANSIENT_PREFIX = "fail-";
	private static final String PERMANENT_PREFIX = "permanent-fail-";

	private final double failureRate;

	protected AbstractMockSender(double failureRate) {
		this.failureRate = failureRate;
	}

	@Override
	public void send(Notification notification) {
		String recipient = notification.getRecipientId();
		if (recipient != null && recipient.startsWith(PERMANENT_PREFIX)) {
			throw new SenderPermanentException("INVALID_RECIPIENT",
					"영구 실패 대상 수신자: " + recipient);
		}
		if (recipient != null && recipient.startsWith(TRANSIENT_PREFIX)) {
			throw new SenderTransientException("SEND_TRANSIENT",
					"일시 실패 대상 수신자: " + recipient);
		}
		if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
			throw new SenderTransientException("SEND_TRANSIENT", "무작위 일시 실패(failureRate)");
		}
		deliver(notification);
	}

	/** 실제 발송 대체 — 로그 출력. */
	protected abstract void deliver(Notification notification);
}
