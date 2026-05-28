package com.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.api.dto.DeadLetterStat;
import com.notification.api.dto.NotificationResponse;
import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.exception.NotificationNotFoundException;
import com.notification.exception.NotificationNotRetryableException;
import com.notification.repository.NotificationRepository;
import com.notification.support.DatabaseCleaner;
import com.notification.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class AdminNotificationServiceIT {

	private static final Instant START = Instant.parse("2026-05-28T10:00:00Z");

	@Autowired
	private AdminNotificationService service;

	@Autowired
	private NotificationRepository repository;

	@Autowired
	private DatabaseCleaner databaseCleaner;

	@BeforeEach
	void setUp() {
		databaseCleaner.clean();
	}

	private Notification saveDeadLetter(String eventId, String errorCode, int failures) {
		Notification n = Notification.builder()
				.recipientId("user-1")
				.notificationType(NotificationType.PAYMENT_CONFIRMED)
				.channel(NotificationChannel.EMAIL)
				.eventId(eventId)
				.createdAt(START)
				.build();
		Instant t = START;
		for (int i = 0; i < failures; i++) {
			n.startProcessing(t, t.plusSeconds(300));
			n.markFailed(errorCode, "msg", t.plusSeconds(60), t.plusSeconds(1));
			t = t.plusSeconds(120);
		}
		n.startProcessing(t, t.plusSeconds(300));
		n.markDeadLetter(errorCode, "final", t.plusSeconds(1));
		return repository.saveAndFlush(n);
	}

	@Test
	@DisplayName("manualRetry: DEAD_LETTER → PENDING 재큐잉, retry_count 유지")
	void manualRetry_requeuesAndKeepsRetryCount() {
		Notification dl = saveDeadLetter("evt-1", "SMTP_TIMEOUT", 4);
		assertThat(dl.getRetryCount()).isEqualTo(4);

		NotificationResponse res = service.manualRetry(dl.getId());

		assertThat(res.status()).isEqualTo(NotificationStatus.PENDING);
		assertThat(res.retryCount()).isEqualTo(4); // 누적 유지
		Notification reloaded = repository.findById(dl.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(reloaded.getNextRetryAt()).isNotNull();
		assertThat(reloaded.getDeadLetterAt()).isNull();
	}

	@Test
	@DisplayName("manualRetry: DEAD_LETTER가 아니면 NotRetryable")
	void manualRetry_notDeadLetter_throws() {
		Notification pending = repository.saveAndFlush(Notification.builder()
				.recipientId("user-1").notificationType(NotificationType.PAYMENT_CONFIRMED)
				.channel(NotificationChannel.EMAIL).eventId("evt-2").createdAt(START).build());

		assertThatThrownBy(() -> service.manualRetry(pending.getId()))
				.isInstanceOf(NotificationNotRetryableException.class);
	}

	@Test
	@DisplayName("manualRetry: 없는 알림이면 NotFound")
	void manualRetry_missing_throws() {
		assertThatThrownBy(() -> service.manualRetry(999_999L))
				.isInstanceOf(NotificationNotFoundException.class);
	}

	@Test
	@DisplayName("batchRetry: errorCode 일치 DEAD_LETTER만 재큐잉")
	void batchRetry_byErrorCode() {
		saveDeadLetter("evt-1", "SMTP_TIMEOUT", 4);
		saveDeadLetter("evt-2", "SMTP_TIMEOUT", 4);
		saveDeadLetter("evt-3", "INVALID_EMAIL", 4);

		int requeued = service.batchRetry("SMTP_TIMEOUT");

		assertThat(requeued).isEqualTo(2);
		assertThat(repository.findByStatusOrderByCreatedAtDesc(NotificationStatus.DEAD_LETTER))
				.hasSize(1); // INVALID_EMAIL 한 건 남음
	}

	@Test
	@DisplayName("batchRetry: errorCode null이면 전체 DEAD_LETTER 재큐잉")
	void batchRetry_all() {
		saveDeadLetter("evt-1", "SMTP_TIMEOUT", 4);
		saveDeadLetter("evt-2", "INVALID_EMAIL", 4);

		int requeued = service.batchRetry(null);

		assertThat(requeued).isEqualTo(2);
		assertThat(repository.findByStatusOrderByCreatedAtDesc(NotificationStatus.DEAD_LETTER))
				.isEmpty();
	}

	@Test
	@DisplayName("listDeadLetters / deadLetterStats: 모니터링 조회")
	void monitoring() {
		saveDeadLetter("evt-1", "SMTP_TIMEOUT", 4);
		saveDeadLetter("evt-2", "SMTP_TIMEOUT", 4);
		saveDeadLetter("evt-3", "INVALID_EMAIL", 4);

		assertThat(service.listDeadLetters()).hasSize(3);

		List<DeadLetterStat> stats = service.deadLetterStats();
		assertThat(stats).extracting(DeadLetterStat::errorCode)
				.containsExactlyInAnyOrder("SMTP_TIMEOUT", "INVALID_EMAIL");
		assertThat(stats).filteredOn(s -> s.errorCode().equals("SMTP_TIMEOUT"))
				.first().extracting(DeadLetterStat::count).isEqualTo(2L);
	}
}
