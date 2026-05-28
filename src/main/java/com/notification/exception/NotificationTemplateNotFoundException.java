package com.notification.exception;

import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;

public class NotificationTemplateNotFoundException extends RuntimeException {

	public NotificationTemplateNotFoundException(
			NotificationType type, NotificationChannel channel, String language) {
		super("활성 템플릿을 찾을 수 없습니다: type=" + type + ", channel=" + channel + ", language=" + language);
	}
}
