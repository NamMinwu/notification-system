package com.notification.api.dto;

import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationTemplate;
import com.notification.domain.NotificationType;

public record TemplateResponse(
		Long id,
		NotificationType notificationType,
		NotificationChannel channel,
		String language,
		String subject,
		String body,
		boolean active) {

	public static TemplateResponse from(NotificationTemplate t) {
		return new TemplateResponse(t.getId(), t.getNotificationType(), t.getChannel(),
				t.getLanguage(), t.getSubject(), t.getBody(), t.isActive());
	}
}
