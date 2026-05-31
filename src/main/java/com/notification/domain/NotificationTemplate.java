package com.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 타입·채널·언어별 메시지 템플릿. Mustache 문법(subject/body)을 저장하고 발송 시 렌더링한다. */
@Entity
@Table(name = "notification_template")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationTemplate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "notification_type", nullable = false)
	private NotificationType notificationType;

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false)
	private NotificationChannel channel;

	@Column(name = "language", nullable = false)
	private String language;

	@Column(name = "subject")
	private String subject;

	@Column(name = "body", nullable = false)
	private String body;

	@Column(name = "is_active", nullable = false)
	private boolean active;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@lombok.Builder(builderClassName = "Builder")
	private NotificationTemplate(NotificationType notificationType, NotificationChannel channel,
			String language, String subject, String body, Instant createdAt) {
		this.notificationType = notificationType;
		this.channel = channel;
		this.language = language;
		this.subject = subject;
		this.body = body;
		this.active = true;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	/** 운영 중 문구 수정. */
	public void updateContent(String subject, String body, Instant now) {
		this.subject = subject;
		this.body = body;
		this.updatedAt = now;
	}
}
