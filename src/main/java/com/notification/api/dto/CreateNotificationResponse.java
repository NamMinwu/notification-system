package com.notification.api.dto;

import com.notification.domain.NotificationStatus;

/** 발송 요청 접수 응답. {@code duplicated=true}면 기존 알림을 멱등 반환한 것. */
public record CreateNotificationResponse(Long id, NotificationStatus status, boolean duplicated) {
}
