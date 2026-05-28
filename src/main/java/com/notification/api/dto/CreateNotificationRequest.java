package com.notification.api.dto;

import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;

/**
 * 알림 발송 요청. {@code eventId}가 없으면 중복 방지(dedup) 대상에서 제외된다(관리자 단발 발송).
 * {@code scheduledAt}이 있으면 해당 시각에 발송, 없으면 즉시 발송 대상.
 */
public record CreateNotificationRequest(
		@NotBlank String recipientId,
		@NotNull NotificationType notificationType,
		@NotNull NotificationChannel channel,
		String eventId,
		Map<String, Object> payload,
		Instant scheduledAt) {
}
