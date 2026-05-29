package com.notification.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.notification.api.dto.NotificationResponse;
import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.repository.NotificationRepository;
import com.notification.sender.NotificationDispatcher;
import com.notification.sender.SenderTransientException;
import com.notification.service.AdminNotificationService;
import com.notification.support.DatabaseCleaner;
import com.notification.support.IntegrationTest;
import com.notification.support.MutableClock;
import com.notification.worker.NotificationSweeper;
import com.notification.worker.NotificationWorker;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 복구 시나리오 end-to-end: 실패/좀비/수동개입이 실제로 다시 발송(SENT)까지 이어지는지 검증.
 * dispatcher를 spy해 "1회 실패 후 성공" 같은 시간적 동작을 시뮬레이션한다.
 */
@IntegrationTest
@Import(NotificationRecoveryIT.ClockTestConfig.class)
class NotificationRecoveryIT {

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
	private NotificationSweeper sweeper;

	@Autowired
	private AdminNotificationService adminService;

	@Autowired
	private NotificationRepository repository;

	@Autowired
	private DatabaseCleaner databaseCleaner;

	@Autowired
	private Clock clock;

	@MockitoSpyBean
	private NotificationDispatcher dispatcher;

	private MutableClock clock() {
		return (MutableClock) clock;
	}

	@BeforeEach
	void setUp() {
		databaseCleaner.clean();
		clock().setInstant(START);
	}

	private Notification savePending(String eventId) {
		return repository.saveAndFlush(Notification.builder()
				.recipientId("user-1")
				.notificationType(NotificationType.PAYMENT_CONFIRMED)
				.channel(NotificationChannel.EMAIL)
				.eventId(eventId)
				.createdAt(START)
				.build());
	}

	private Notification reload(Long id) {
		return repository.findById(id).orElseThrow();
	}

	@Test
	@DisplayName("A. 일시 실패 → backoff 후 재시도가 성공하면 SENT (재시도 회복)")
	void transientFailure_thenRecoversToSent() {
		Long id = savePending("evt-1").getId();
		// 1번째 발송은 일시 실패, 2번째는 성공
		doThrow(new SenderTransientException("SMTP_TIMEOUT", "temporary"))
				.doNothing()
				.when(dispatcher).dispatch(any());

		worker.runOnce(); // 시도 1 → 실패
		Notification afterFail = reload(id);
		assertThat(afterFail.getStatus()).isEqualTo(NotificationStatus.FAILED);
		assertThat(afterFail.getRetryCount()).isEqualTo(1);
		assertThat(afterFail.getNextRetryAt()).isAfter(START);

		clock().advance(Duration.ofSeconds(20)); // next_retry_at 도래
		worker.runOnce(); // 시도 2 → 성공

		Notification recovered = reload(id);
		assertThat(recovered.getStatus()).isEqualTo(NotificationStatus.SENT);
		assertThat(recovered.getRetryCount()).isEqualTo(1); // 누적 유지
		verify(dispatcher, times(2)).dispatch(any());
	}

	@Test
	@DisplayName("B. DEAD_LETTER 수동 재시도 → 워커가 재처리해 SENT")
	void deadLetter_manualRetry_recoversToSent() {
		Notification n = savePending("evt-2");
		n.startProcessing(START, START.plus(Duration.ofMinutes(10)));
		n.markDeadLetter("INVALID", "final", START.plusSeconds(1));
		Long id = repository.saveAndFlush(n).getId();

		NotificationResponse res = adminService.manualRetry(id); // DEAD_LETTER → PENDING(now)
		assertThat(res.status()).isEqualTo(NotificationStatus.PENDING);

		worker.runOnce(); // 재큐잉된 건을 픽업 → 성공

		assertThat(reload(id).getStatus()).isEqualTo(NotificationStatus.SENT);
	}

	@Test
	@DisplayName("C. PROCESSING 좀비 → Sweeper 복구 → 워커가 재처리해 SENT (재시작 유실 X)")
	void processingZombie_sweptThenRecoversToSent() {
		Notification n = savePending("evt-3");
		n.startProcessing(START, START.plus(Duration.ofMinutes(5))); // lease 5분
		Long id = repository.saveAndFlush(n).getId();

		clock().advance(Duration.ofMinutes(10)); // lease 만료
		int recovered = sweeper.sweep();
		assertThat(recovered).isEqualTo(1);
		assertThat(reload(id).getStatus()).isEqualTo(NotificationStatus.PENDING);

		worker.runOnce(); // 복구된 건을 픽업 → 성공

		assertThat(reload(id).getStatus()).isEqualTo(NotificationStatus.SENT);
	}
}
