package com.notification.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.repository.NotificationRepository;
import com.notification.sender.NotificationDispatcher;
import com.notification.support.DatabaseCleaner;
import com.notification.support.IntegrationTest;
import com.notification.worker.NotificationWorker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 다중 인스턴스 동시성: 여러 워커가 동시에 폴링해도 FOR UPDATE SKIP LOCKED로 각 알림이 정확히 한 번만
 * 처리되는지 검증. dispatcher를 spy해 발송 호출 횟수가 알림 수와 정확히 일치함을 확인한다.
 */
@IntegrationTest
class WorkerConcurrencyIT {

	private static final int NOTIFICATION_COUNT = 100;
	private static final int WORKER_THREADS = 4;

	@Autowired
	private NotificationWorker worker;

	@Autowired
	private NotificationRepository repository;

	@Autowired
	private DatabaseCleaner databaseCleaner;

	@MockitoSpyBean
	private NotificationDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		databaseCleaner.clean();
	}

	@Test
	@DisplayName("다중 워커 동시 폴링 → 각 알림 정확히 1회 발송 (중복 처리 없음)")
	void multipleWorkers_eachNotificationDispatchedExactlyOnce() throws Exception {
		seedPending(NOTIFICATION_COUNT);

		ExecutorService executor = Executors.newFixedThreadPool(WORKER_THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(WORKER_THREADS);
		for (int i = 0; i < WORKER_THREADS; i++) {
			executor.submit(() -> {
				try {
					start.await();
					for (int round = 0; round < 10; round++) {
						worker.runOnce();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		done.await(60, TimeUnit.SECONDS);
		executor.shutdownNow();

		// 모든 알림이 정확히 한 번씩 발송되고 SENT로 종료
		verify(dispatcher, times(NOTIFICATION_COUNT)).dispatch(any());
		assertThat(repository.findByStatusOrderByCreatedAtDesc(NotificationStatus.DEAD_LETTER)).isEmpty();
		long sent = repository.findAll().stream()
				.filter(n -> n.getStatus() == NotificationStatus.SENT)
				.count();
		assertThat(sent).isEqualTo(NOTIFICATION_COUNT);
	}

	private void seedPending(int count) {
		List<Notification> batch = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			batch.add(Notification.builder()
					.recipientId("user-" + i)
					.notificationType(NotificationType.PAYMENT_CONFIRMED)
					.channel(NotificationChannel.EMAIL)
					.eventId("evt-" + i)
					.createdAt(Instant.parse("2026-05-28T10:00:00Z"))
					.build());
		}
		repository.saveAll(batch);
	}
}
