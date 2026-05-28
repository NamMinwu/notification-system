package com.notification.worker;

import com.notification.config.NotificationProperties;
import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import com.notification.repository.NotificationRepository;
import com.notification.sender.NotificationDispatcher;
import com.notification.sender.NotificationSendException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 처리 트랜잭션 단위. Worker(오케스트레이션)와 분리되어 self-invocation 없이 프록시 트랜잭션이 동작한다.
 * 잠금/전이(claimBatch)와 발송/결과 반영(process)을 각각 독립 트랜잭션으로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationProcessor {

	private final NotificationRepository repository;
	private final NotificationDispatcher dispatcher;
	private final RetryPolicy retryPolicy;
	private final ErrorClassifier classifier;
	private final NotificationProperties properties;
	private final Clock clock;

	/** 발송 시각이 도래한 배치를 SKIP LOCKED로 잠그고 PROCESSING+lease로 전이. 처리할 id 목록 반환. */
	@Transactional
	public List<Long> claimBatch() {
		Instant now = clock.instant();
		List<Notification> batch =
				repository.findBatchForProcessing(now, properties.polling().batchSize());
		Instant leaseExpiresAt = now.plus(properties.sweeper().leaseTimeout());
		for (Notification n : batch) {
			n.startProcessing(now, leaseExpiresAt);
		}
		return batch.stream().map(Notification::getId).toList();
	}

	/** 단건 발송 + 결과 반영. 각 알림이 독립 트랜잭션이라 실패가 서로 격리된다. */
	@Transactional
	public void process(Long id) {
		Notification n = repository.findById(id).orElse(null);
		if (n == null || n.getStatus() != NotificationStatus.PROCESSING) {
			return; // 이미 다른 경로(Sweeper/중복 픽업)로 처리됨
		}
		Instant now = clock.instant();
		try {
			dispatcher.dispatch(n);
			n.markSent(now);
		} catch (NotificationSendException e) {
			// 예상된 발송 실패(일시/영구) — 분류해 재시도 또는 DEAD_LETTER.
			handleFailure(n, e, now);
		} catch (RuntimeException e) {
			// 예기치 못한 오류(버그/인프라). 크게 로깅해 가시화하되, 상태는 정리해 PROCESSING 좀비를 막는다.
			// rethrow하면 롤백 → PROCESSING 잔류 → Sweeper가 retry_count 증가 없이 무한 복구하므로 지양.
			log.error("notification 처리 중 예기치 못한 오류 id={} (재시도 처리)", n.getId(), e);
			handleFailure(n, e, now);
		}
	}

	private void handleFailure(Notification n, RuntimeException e, Instant now) {
		String code = classifier.errorCode(e);
		String message = e.getMessage();
		int attemptsMade = n.getRetryCount() + 1;
		if (!classifier.isRetryable(e) || retryPolicy.hasReachedMaxAttempts(attemptsMade)) {
			log.warn("notification dead-letter id={} attempts={} code={}", n.getId(), attemptsMade, code);
			n.markDeadLetter(code, message, now);
		} else {
			Instant next = retryPolicy.nextRetryAt(attemptsMade, now);
			log.info("notification retry scheduled id={} attempt={} nextRetryAt={} code={}",
					n.getId(), attemptsMade, next, code);
			n.markFailed(code, message, next, now);
		}
	}
}
