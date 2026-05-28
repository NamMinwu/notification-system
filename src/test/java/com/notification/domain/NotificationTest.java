package com.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationTest {

	private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");

	private Notification newPending() {
		return Notification.builder()
				.recipientId("user-1")
				.notificationType(NotificationType.PAYMENT_CONFIRMED)
				.channel(NotificationChannel.EMAIL)
				.eventId("evt-1")
				.payload(Map.of("amount", 5000))
				.createdAt(NOW)
				.build();
	}

	@Test
	@DisplayName("생성 시 PENDING, retryCount 0, 미읽음 상태")
	void create_startsPending() {
		Notification n = newPending();

		assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(n.getRetryCount()).isZero();
		assertThat(n.isRead()).isFalse();
		assertThat(n.getRecipientId()).isEqualTo("user-1");
		assertThat(n.getEventId()).isEqualTo("evt-1");
		assertThat(n.getPayload()).containsEntry("amount", 5000);
	}

	@Test
	@DisplayName("startProcessing: PENDING → PROCESSING, lease/처리시작 시각 설정")
	void startProcessing_setsLease() {
		Notification n = newPending();
		Instant lease = NOW.plusSeconds(300);

		n.startProcessing(NOW, lease);

		assertThat(n.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
		assertThat(n.getProcessingStartedAt()).isEqualTo(NOW);
		assertThat(n.getLeaseExpiresAt()).isEqualTo(lease);
	}

	@Test
	@DisplayName("markSent: PROCESSING → SENT, sentAt 설정 및 lease 해제")
	void markSent() {
		Notification n = newPending();
		n.startProcessing(NOW, NOW.plusSeconds(300));

		n.markSent(NOW.plusSeconds(1));

		assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
		assertThat(n.getSentAt()).isEqualTo(NOW.plusSeconds(1));
		assertThat(n.getLeaseExpiresAt()).isNull();
	}

	@Test
	@DisplayName("markFailed: PROCESSING → FAILED, retryCount 증가 + 실패 사유 + nextRetryAt")
	void markFailed() {
		Notification n = newPending();
		n.startProcessing(NOW, NOW.plusSeconds(300));
		Instant nextRetry = NOW.plusSeconds(60);

		n.markFailed("SMTP_TIMEOUT", "Connection timeout", nextRetry, NOW.plusSeconds(1));

		assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
		assertThat(n.getRetryCount()).isEqualTo(1);
		assertThat(n.getLastErrorCode()).isEqualTo("SMTP_TIMEOUT");
		assertThat(n.getLastErrorMessage()).isEqualTo("Connection timeout");
		assertThat(n.getNextRetryAt()).isEqualTo(nextRetry);
		assertThat(n.getFailedAt()).isEqualTo(NOW.plusSeconds(1));
		assertThat(n.getLeaseExpiresAt()).isNull();
	}

	@Test
	@DisplayName("FAILED 상태에서 재시도(startProcessing) 후 다시 성공할 수 있다")
	void retryAfterFailure() {
		Notification n = newPending();
		n.startProcessing(NOW, NOW.plusSeconds(300));
		n.markFailed("SMTP_TIMEOUT", "timeout", NOW.plusSeconds(60), NOW.plusSeconds(1));

		n.startProcessing(NOW.plusSeconds(60), NOW.plusSeconds(360));
		n.markSent(NOW.plusSeconds(61));

		assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
		assertThat(n.getRetryCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("markDeadLetter: PROCESSING → DEAD_LETTER")
	void markDeadLetter_fromProcessing() {
		Notification n = newPending();
		n.startProcessing(NOW, NOW.plusSeconds(300));

		n.markDeadLetter("INVALID_EMAIL", "bad address", NOW.plusSeconds(1));

		assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
		assertThat(n.getDeadLetterAt()).isEqualTo(NOW.plusSeconds(1));
		assertThat(n.getLastErrorCode()).isEqualTo("INVALID_EMAIL");
	}

	@Test
	@DisplayName("markDeadLetter: FAILED → DEAD_LETTER (재시도 한도 초과)")
	void markDeadLetter_fromFailed() {
		Notification n = newPending();
		n.startProcessing(NOW, NOW.plusSeconds(300));
		n.markFailed("SMTP_TIMEOUT", "timeout", NOW.plusSeconds(60), NOW.plusSeconds(1));

		n.markDeadLetter("SMTP_TIMEOUT", "max retries", NOW.plusSeconds(2));

		assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
	}

	@Test
	@DisplayName("recoverToPending: PROCESSING → PENDING (좀비 복구), lease/처리시작 초기화")
	void recoverToPending() {
		Notification n = newPending();
		n.startProcessing(NOW, NOW.plusSeconds(300));

		n.recoverToPending(NOW.plusSeconds(301));

		assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(n.getLeaseExpiresAt()).isNull();
		assertThat(n.getProcessingStartedAt()).isNull();
	}

	@Test
	@DisplayName("retry: DEAD_LETTER → PENDING 재큐잉, retry_count 유지 + next_retry_at 즉시")
	void retry_requeuesFromDeadLetter() {
		Notification n = newPending();
		n.startProcessing(NOW, NOW.plusSeconds(300));
		n.markDeadLetter("INVALID_EMAIL", "bad", NOW.plusSeconds(1));
		int retryCountBefore = n.getRetryCount();

		Instant retryAt = NOW.plusSeconds(100);
		n.retry(retryAt);

		assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(n.getRetryCount()).isEqualTo(retryCountBefore); // 누적 유지
		assertThat(n.getNextRetryAt()).isEqualTo(retryAt);         // 즉시 픽업 대상
		assertThat(n.getDeadLetterAt()).isNull();
		assertThat(n.getLastErrorCode()).isEqualTo("INVALID_EMAIL"); // 이력 보존
	}

	@Test
	@DisplayName("retry: DEAD_LETTER가 아니면 IllegalState")
	void retry_onlyFromDeadLetter() {
		Notification n = newPending();

		assertThatThrownBy(() -> n.retry(NOW))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("cancel: PENDING → CANCELLED")
	void cancel() {
		Notification n = newPending();

		n.cancel(NOW.plusSeconds(5));

		assertThat(n.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
		assertThat(n.getCancelledAt()).isEqualTo(NOW.plusSeconds(5));
	}

	@Test
	@DisplayName("markRead: 미읽음 → 읽음, readAt 설정")
	void markRead() {
		Notification n = newPending();

		boolean changed = n.markRead(NOW.plusSeconds(10));

		assertThat(changed).isTrue();
		assertThat(n.isRead()).isTrue();
		assertThat(n.getReadAt()).isEqualTo(NOW.plusSeconds(10));
	}

	@Test
	@DisplayName("markRead 두 번째 호출은 멱등 — readAt은 첫 시각 유지")
	void markRead_idempotent() {
		Notification n = newPending();
		n.markRead(NOW.plusSeconds(10));

		boolean changed = n.markRead(NOW.plusSeconds(20));

		assertThat(changed).isFalse();
		assertThat(n.getReadAt()).isEqualTo(NOW.plusSeconds(10));
	}

	@Test
	@DisplayName("잘못된 전이는 IllegalStateException")
	void invalidTransition_throws() {
		Notification n = newPending();

		assertThatThrownBy(() -> n.markSent(NOW))
				.isInstanceOf(IllegalStateException.class);

		n.startProcessing(NOW, NOW.plusSeconds(300));
		assertThatThrownBy(() -> n.cancel(NOW))
				.isInstanceOf(IllegalStateException.class);
	}
}
