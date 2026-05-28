package com.notification.worker;

import com.notification.config.NotificationProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 폴링 기반 발송 메커니즘(Outbox). 배치를 {@code FOR UPDATE SKIP LOCKED}로 잠가 PROCESSING으로
 * 전이한 뒤, 각 알림을 순차 발송한다. 각 발송은 독립 트랜잭션이라 단건 실패가 격리된다.
 *
 * <p>처리량 확장의 1차 수단은 <b>인스턴스 추가</b>다 — SKIP LOCKED가 인스턴스 간 중복 없이
 * 작업을 분배한다. 인스턴스 내부 병렬(고정 스레드풀 등)은 실제 sender I/O가 느리고 처리량 요구가
 * 생길 때 도입하며, 그 경우 발송을 트랜잭션 밖으로 빼고 sender 멱등성을 함께 갖춘다(01-ASYNC-RETRY 참고).
 *
 * <p>운영 전환 시 이 폴링 부분을 Kafka Consumer로 교체하면 된다(Processor/Sender 재사용).
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
		for (Long id : ids) {
			try {
				processor.process(id);
			} catch (RuntimeException e) {
				// 단건의 예기치 못한 실패가 배치의 나머지를 막지 않도록 격리
				log.error("notification processing failed unexpectedly id={}", id, e);
			}
		}
	}
}
