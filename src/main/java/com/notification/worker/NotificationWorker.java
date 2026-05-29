package com.notification.worker;

import com.notification.config.NotificationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 폴링 기반 발송 메커니즘(Outbox). 배치를 {@code FOR UPDATE SKIP LOCKED}로 잠가 PROCESSING으로
 * 전이한 뒤, 발송 전용 <b>고정 스레드풀</b>({@code worker.concurrency})로 각 알림을 처리한다.
 * 동시 발송 수는 외부 자원 한도에 맞춰 의도적으로 제한되며, 각 발송은 독립 트랜잭션이라 실패가 격리된다.
 *
 * <p>확장: ① 인스턴스 추가(SKIP LOCKED 수평) ② 폴링/배치 튜닝 ③ {@code worker.concurrency} 상향.
 * 발송 동시성이 매우 커지면(수십+) 발송을 트랜잭션 밖으로 빼고 sender 멱등성을 도입한다(01-ASYNC-RETRY).
 * 운영 전환 시 이 폴링 부분만 Kafka Consumer로 교체하면 된다(Processor/Sender 재사용).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationWorker {

	private final NotificationProcessor processor;
	private final NotificationProperties properties;
	private final ExecutorService sendExecutor;

	@Scheduled(fixedDelayString = "${notification.polling.interval}")
	public void poll() {
		if (!properties.schedulingEnabled()) {
			return;
		}
		runOnce();
	}

	/**
	 * 한 번의 폴링 사이클: claim한 배치를 고정 풀에 제출하고 모두 끝날 때까지 대기(다음 사이클 전 완료 보장).
	 * 테스트에서 직접 호출해 결정적으로 검증한다.
	 */
	public void runOnce() {
		List<Long> ids = processor.claimBatch();
		if (ids.isEmpty()) {
			return;
		}
		List<Future<?>> futures = new ArrayList<>(ids.size());
		for (Long id : ids) {
			futures.add(sendExecutor.submit(() -> dispatchOne(id)));
		}
		for (Future<?> future : futures) {
			try {
				future.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (ExecutionException e) {
				log.error("notification dispatch task failed", e.getCause());
			}
		}
	}

	private void dispatchOne(Long id) {
		try {
			processor.process(id);
		} catch (RuntimeException e) {
			// 단건의 예기치 못한 실패가 배치의 나머지를 막지 않도록 격리
			log.error("notification processing failed unexpectedly id={}", id, e);
		}
	}
}
