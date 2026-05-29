package com.notification.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import com.notification.repository.NotificationRepository;
import com.notification.service.NotificationService;
import com.notification.support.DatabaseCleaner;
import com.notification.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 동시성 검증: 중복 방지(멱등 INSERT)와 읽음 멱등 처리. */
@IntegrationTest
class NotificationConcurrencyIT {

	@Autowired
	private NotificationService service;

	@Autowired
	private NotificationRepository repository;

	@Autowired
	private DatabaseCleaner databaseCleaner;

	@BeforeEach
	void setUp() {
		databaseCleaner.clean();
	}

	@Test
	@DisplayName("동일 이벤트 100건 동시 생성 → 정확히 1건만 저장, 같은 id 반환")
	void concurrentDuplicateCreate_onlyOneRow() throws Exception {
		int threads = 100;
		CreateNotificationRequest request = new CreateNotificationRequest(
				"user-1", NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL,
				"evt-1", Map.of("amount", 5000), null);

		List<Long> ids = new CopyOnWriteArrayList<>();
		AtomicInteger newCount = new AtomicInteger();
		runConcurrently(threads, () -> {
			CreateNotificationResponse res = service.create(request);
			ids.add(res.id());
			if (!res.duplicated()) {
				newCount.incrementAndGet();
			}
		});

		assertThat(repository.count()).isEqualTo(1);
		assertThat(Set.copyOf(ids)).hasSize(1);          // 모두 같은 id
		assertThat(newCount.get()).isEqualTo(1);          // 신규는 정확히 1건, 나머지는 멱등
	}

	@Test
	@DisplayName("동일 알림 50건 동시 읽음 → 읽음 처리되고 read_at이 단일 값으로 고정(첫 시각 유지)")
	void concurrentMarkAsRead_readAtFixed() throws Exception {
		Long id = service.create(new CreateNotificationRequest(
				"user-1", NotificationType.ENROLLMENT_COMPLETE, NotificationChannel.IN_APP,
				"evt-1", Map.of(), null)).id();

		runConcurrently(50, () -> service.markAsRead(id, "user-1"));

		var afterBurst = repository.findById(id).orElseThrow();
		assertThat(afterBurst.isRead()).isTrue();
		assertThat(afterBurst.getReadAt()).isNotNull();

		// 추가 읽음 호출은 멱등(WHERE is_read=false) → read_at이 바뀌지 않음 = 첫 시각 고정 입증
		service.markAsRead(id, "user-1");
		assertThat(repository.findById(id).orElseThrow().getReadAt())
				.isEqualTo(afterBurst.getReadAt());
	}

	private void runConcurrently(int threadCount, Runnable action) throws InterruptedException {
		// 시작 배리어(ready/start)에서 모든 태스크가 동시에 대기해야 하므로 무제한 가상 스레드 사용.
		// 고정 풀이 threadCount보다 작으면 일부 태스크가 시작조차 못 해 ready가 0에 못 미쳐 데드락난다.
		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threadCount);
		Set<Throwable> errors = ConcurrentHashMap.newKeySet();

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					action.run();
				} catch (Throwable t) {
					errors.add(t);
				} finally {
					done.countDown();
				}
			});
		}
		ready.await();
		start.countDown(); // 동시에 출발
		done.await(30, TimeUnit.SECONDS);
		executor.shutdownNow();

		assertThat(errors).as("동시 실행 중 예외 없음").isEmpty();
	}
}
