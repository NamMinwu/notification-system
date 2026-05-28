package com.notification.sender;

import com.notification.config.NotificationProperties;
import com.notification.domain.NotificationChannel;
import com.notification.service.NotificationRenderer;
import org.springframework.stereotype.Component;

@Component
public class MockEmailSender extends AbstractMockSender {

	public MockEmailSender(NotificationProperties properties, NotificationRenderer renderer) {
		super(properties.mockSender().failureRate(), renderer);
	}

	@Override
	public NotificationChannel channel() {
		return NotificationChannel.EMAIL;
	}
}
