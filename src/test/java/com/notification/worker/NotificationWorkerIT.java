package com.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.repository.NotificationRepository;
import com.notification.support.DatabaseCleaner;
import com.notification.support.IntegrationTest;
import com.notification.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@IntegrationTest
@Import(NotificationWorkerIT.ClockTestConfig.class)
class NotificationWorkerIT {

	private static final Instant START = Instant.parse("2026-05-28T10:00:00Z");

	@TestConfiguration
	static class ClockTestConfig {
		@Bean
		@Primary
		Clock testClock() {
			return new MutableClock(START);
		}
	}

	@Autowired
	private NotificationWorker worker;

	@Autowired
	private NotificationRepository repository;

	@Autowired
	private DatabaseCleaner databaseCleaner;

	@Autowired
	private Clock clock;

	private MutableClock clock() {
		return (MutableClock) clock;
	}

	@BeforeEach
	void setUp() {
		databaseCleaner.clean();
		clock().setInstant(START);
	}

	private Notification savePending(String recipientId, String eventId, Instant scheduledAt) {
		return repository.saveAndFlush(Notification.builder()
				.recipientId(recipientId)
				.notificationType(NotificationType.PAYMENT_CONFIRMED)
				.channel(NotificationChannel.EMAIL)
				.eventId(eventId)
				.scheduledAt(scheduledAt)
				.createdAt(START)
				.build());
	}

	private Notification reload(Long id) {
		return repository.findById(id).orElseThrow();
	}

	@Test
	@DisplayName("정상 발송: PENDING → runOnce → SENT")
	void success() {
		Long id = savePending("user-1", "evt-1", null).getId();

		worker.runOnce();

		Notification n = reload(id);
		assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
		assertThat(n.getSentAt()).isNotNull();
		assertThat(n.getLeaseExpiresAt()).isNull();
	}

	@Test
	@DisplayName("일시 실패: 재시도 대상 → FAILED, retryCount 증가, nextRetryAt 미래")
	void transientFailure_marksFailed() {
		Long id = savePending("fail-user", "evt-1", null).getId();

		worker.runOnce();

		Notification n = reload(id);
		assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
		assertThat(n.getRetryCount()).isEqualTo(1);
		assertThat(n.getLastErrorCode()).isEqualTo("SEND_TRANSIENT");
		assertThat(n.getNextRetryAt()).isAfter(START);
	}

	@Test
	@DisplayName("영구 실패: 재시도 없이 즉시 DEAD_LETTER")
	void permanentFailure_marksDeadLetter() {
		Long id = savePending("permanent-fail-user", "evt-1", null).getId();

		worker.runOnce();

		Notification n = reload(id);
		assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
		assertThat(n.getRetryCount()).isZero();
		assertThat(n.getLastErrorCode()).isEqualTo("INVALID_RECIPIENT");
	}

	@Test
	@DisplayName("재시도 한도 초과: 5회 시도 후 DEAD_LETTER")
	void exhaustsRetries_thenDeadLetter() {
		Long id = savePending("fail-user", "evt-1", null).getId();

		for (int i = 0; i < 5; i++) {
			worker.runOnce();
			clock().advance(Duration.ofSeconds(20)); // nextRetryAt 도래 보장
		}

		Notification n = reload(id);
		assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
		assertThat(n.getRetryCount()).isEqualTo(4); // 5번째 시도에서 DLQ(증가 없음)
	}

	@Test
	@DisplayName("예약 발송: 예약 시각 전에는 픽업되지 않음")
	void scheduledInFuture_notPicked() {
		Long id = savePending("user-1", "evt-1", START.plus(Duration.ofHours(1))).getId();

		worker.runOnce();

		assertThat(reload(id).getStatus()).isEqualTo(NotificationStatus.PENDING);
	}

	@Test
	@DisplayName("예약 발송: 예약 시각이 도래하면 픽업되어 발송")
	void scheduledDue_isSent() {
		Long id = savePending("user-1", "evt-1", START.minus(Duration.ofMinutes(1))).getId();

		worker.runOnce();

		assertThat(reload(id).getStatus()).isEqualTo(NotificationStatus.SENT);
	}
}
