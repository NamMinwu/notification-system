package com.notification.sender;

import com.notification.domain.Notification;
import com.notification.service.NotificationRenderer;
import com.notification.service.RenderedMessage;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;

/**
 * Mock 발송 공통 로직. 실패 시뮬레이션 후 템플릿을 렌더링해 "발송" 대신 로그로 출력한다.
 * recipientId 접두사로 결정적 실패를 유발하고(테스트용), 설정된 failureRate로 무작위 일시 실패도 낸다.
 */
@Slf4j
public abstract class AbstractMockSender implements NotificationSender {

	private static final String TRANSIENT_PREFIX = "fail-";
	private static final String PERMANENT_PREFIX = "permanent-fail-";

	private final double failureRate;
	private final NotificationRenderer renderer;

	protected AbstractMockSender(double failureRate, NotificationRenderer renderer) {
		this.failureRate = failureRate;
		this.renderer = renderer;
	}

	@Override
	public void send(Notification notification) {
		String recipient = notification.getRecipientId();
		if (recipient != null && recipient.startsWith(PERMANENT_PREFIX)) {
			throw new SenderPermanentException("INVALID_RECIPIENT", "영구 실패 대상 수신자: " + recipient);
		}
		if (recipient != null && recipient.startsWith(TRANSIENT_PREFIX)) {
			throw new SenderTransientException("SEND_TRANSIENT", "일시 실패 대상 수신자: " + recipient);
		}
		if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
			throw new SenderTransientException("SEND_TRANSIENT", "무작위 일시 실패(failureRate)");
		}
		RenderedMessage message = renderer.render(notification);
		log.info("[MOCK-{}] sent id={} recipient={} subject='{}' body='{}'",
				channel(), notification.getId(), recipient, message.subject(), message.body());
	}
}
