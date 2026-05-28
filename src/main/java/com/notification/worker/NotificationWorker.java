package com.notification.worker;

import com.notification.config.NotificationProperties;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 폴링 기반 발송 메커니즘. 배치를 잠가 PROCESSING으로 전이한 뒤,
 * 각 알림을 Virtual Thread로 병렬 발송(외부 부하는 Semaphore로 제한)한다.
 * 운영 전환 시 이 폴링 부분을 Kafka Consumer로 교체하면 된다(Processor/Sender는 재사용).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationWorker {

	private final NotificationProcessor processor;
	private final NotificationProperties properties;

	@Scheduled(fixedDelayString = "${notification.polling.interval}")
	public void poll() {
		if (!properties.schedulingEnabled()) {
			return;
		}
		runOnce();
	}

	/** 한 번의 폴링 사이클. 테스트에서 직접 호출해 결정적으로 검증한다. */
	public void runOnce() {
		List<Long> ids = processor.claimBatch();
		if (ids.isEmpty()) {
			return;
		}
		Semaphore semaphore = new Semaphore(properties.worker().semaphorePermits());
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (Long id : ids) {
				executor.submit(() -> dispatchOne(id, semaphore));
			}
		} // close()가 모든 작업 완료까지 대기
	}

	private void dispatchOne(Long id, Semaphore semaphore) {
		try {
			semaphore.acquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		try {
			processor.process(id);
		} catch (RuntimeException e) {
			log.error("notification processing failed unexpectedly id={}", id, e);
		} finally {
			semaphore.release();
		}
	}
}
