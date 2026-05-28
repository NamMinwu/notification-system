package com.notification.api.dto;

import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 템플릿 업서트 요청. language가 없으면 기본 'ko'. subject는 IN_APP/제목 없는 경우 생략 가능. */
public record TemplateUpsertRequest(
		@NotNull NotificationType notificationType,
		@NotNull NotificationChannel channel,
		@Size(max = 10) String language,
		@Size(max = 200) String subject,
		@NotBlank @Size(max = 10_000) String body) {
}
