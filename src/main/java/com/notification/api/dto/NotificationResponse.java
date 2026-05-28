package com.notification.api.dto;

import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import java.time.Instant;
import java.util.Map;

/** 알림 상태 조회 / 목록 조회 응답. */
public record NotificationResponse(
		Long id,
		String recipientId,
		NotificationType notificationType,
		NotificationChannel channel,
		NotificationStatus status,
		Map<String, Object> payload,
		boolean read,
		Instant readAt,
		Instant scheduledAt,
		int retryCount,
		String lastErrorCode,
		Instant createdAt) {

	public static NotificationResponse from(Notification n) {
		return new NotificationResponse(
				n.getId(),
				n.getRecipientId(),
				n.getNotificationType(),
				n.getChannel(),
				n.getStatus(),
				n.getPayload(),
				n.isRead(),
				n.getReadAt(),
				n.getScheduledAt(),
				n.getRetryCount(),
				n.getLastErrorCode(),
				n.getCreatedAt());
	}
}
