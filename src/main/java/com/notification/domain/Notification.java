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
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 알림 Outbox 엔티티. 상태 전이는 {@link NotificationStatus#canTransitionTo}로 도메인 레벨에서 강제한다.
 * 모든 시각은 UTC(Instant). 도메인 메서드는 Clock에 의존하지 않도록 {@code now}를 인자로 받는다.
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id")
	private String eventId;

	@Column(name = "recipient_id", nullable = false)
	private String recipientId;

	@Enumerated(EnumType.STRING)
	@Column(name = "notification_type", nullable = false)
	private NotificationType notificationType;

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false)
	private NotificationChannel channel;

	@Getter(AccessLevel.NONE)
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", columnDefinition = "jsonb")
	private Map<String, Object> payload;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private NotificationStatus status;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "scheduled_at")
	private Instant scheduledAt;

	@Column(name = "next_retry_at")
	private Instant nextRetryAt;

	@Column(name = "lease_expires_at")
	private Instant leaseExpiresAt;

	@Column(name = "last_error_code")
	private String lastErrorCode;

	@Column(name = "last_error_message")
	private String lastErrorMessage;

	@Column(name = "is_read", nullable = false)
	private boolean read;

	@Column(name = "read_at")
	private Instant readAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@lombok.Builder(builderClassName = "Builder")
	private Notification(String eventId, String recipientId, NotificationType notificationType,
			NotificationChannel channel, Map<String, Object> payload, Instant scheduledAt,
			Instant createdAt) {
		this.eventId = eventId;
		this.recipientId = recipientId;
		this.notificationType = notificationType;
		this.channel = channel;
		this.payload = payload;
		this.scheduledAt = scheduledAt;
		this.status = NotificationStatus.PENDING;
		this.retryCount = 0;
		this.read = false;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	// --- 상태 전이 ---

	public void startProcessing(Instant now, Instant leaseExpiresAt) {
		transitionTo(NotificationStatus.PROCESSING, now);
		this.leaseExpiresAt = leaseExpiresAt;
	}

	public void markSent(Instant now) {
		transitionTo(NotificationStatus.SENT, now);
		this.leaseExpiresAt = null;
	}

	public void markFailed(String errorCode, String errorMessage, Instant nextRetryAt, Instant now) {
		transitionTo(NotificationStatus.FAILED, now);
		this.retryCount += 1;
		this.lastErrorCode = errorCode;
		this.lastErrorMessage = errorMessage;
		this.nextRetryAt = nextRetryAt;
		this.leaseExpiresAt = null;
	}

	public void markDeadLetter(String errorCode, String errorMessage, Instant now) {
		transitionTo(NotificationStatus.DEAD_LETTER, now);
		this.lastErrorCode = errorCode;
		this.lastErrorMessage = errorMessage;
		this.leaseExpiresAt = null;
	}

	public void recoverToPending(Instant now) {
		transitionTo(NotificationStatus.PENDING, now);
		this.leaseExpiresAt = null;
	}

	public void cancel(Instant now) {
		transitionTo(NotificationStatus.CANCELLED, now);
	}

	/**
	 * 수동 재시도: DEAD_LETTER → PENDING 재큐잉. retry_count·last_error는 유지(이력 보존)하고
	 * next_retry_at을 즉시로 두어 다음 폴링에서 곧바로 픽업되게 한다.
	 */
	public void retry(Instant now) {
		transitionTo(NotificationStatus.PENDING, now);
		this.nextRetryAt = now;
		this.leaseExpiresAt = null;
	}

	/** 멱등 읽음 처리. 이미 읽었으면 false 반환(readAt 첫 시각 유지). */
	public boolean markRead(Instant now) {
		if (this.read) {
			return false;
		}
		this.read = true;
		this.readAt = now;
		this.updatedAt = now;
		return true;
	}

	/** 방어적 복사로 가변 payload 누출 방지. */
	public Map<String, Object> getPayload() {
		return payload == null ? null : Map.copyOf(payload);
	}

	private void transitionTo(NotificationStatus target, Instant now) {
		if (!status.canTransitionTo(target)) {
			throw new IllegalStateException(
					"잘못된 상태 전이: " + status + " → " + target + " (id=" + id + ")");
		}
		this.status = target;
		this.updatedAt = now;
	}
}
