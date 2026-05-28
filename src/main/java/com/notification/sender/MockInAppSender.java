package com.notification.sender;

import com.notification.config.NotificationProperties;
import com.notification.domain.NotificationChannel;
import com.notification.service.TemplateRenderer;
import org.springframework.stereotype.Component;

@Component
public class MockInAppSender extends AbstractMockSender {

	public MockInAppSender(NotificationProperties properties, TemplateRenderer renderer) {
		super(properties.mockSender().failureRate(), renderer);
	}

	@Override
	public NotificationChannel channel() {
		return NotificationChannel.IN_APP;
	}
}
