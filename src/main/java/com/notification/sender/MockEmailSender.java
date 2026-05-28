package com.notification.sender;

import com.notification.config.NotificationProperties;
import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MockEmailSender extends AbstractMockSender {

	public MockEmailSender(NotificationProperties properties) {
		super(properties.mockSender().failureRate());
	}

	@Override
	protected void deliver(Notification notification) {
		log.info("[MOCK-EMAIL] sent id={} recipient={} type={}",
				notification.getId(), notification.getRecipientId(), notification.getNotificationType());
	}

	@Override
	public NotificationChannel channel() {
		return NotificationChannel.EMAIL;
	}
}
