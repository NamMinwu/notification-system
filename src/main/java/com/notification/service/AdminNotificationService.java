package com.notification.service;

import com.notification.api.dto.DeadLetterStat;
import com.notification.api.dto.NotificationResponse;
import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import com.notification.exception.NotificationNotFoundException;
import com.notification.exception.NotificationNotRetryableException;
import com.notification.repository.NotificationRepository;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영자 도구: DEAD_LETTER 수동 재시도 및 모니터링. retry_count는 유지(누적 이력 보존). */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationService {

	private final NotificationRepository repository;
	private final Clock clock;

	@Transactional
	public NotificationResponse manualRetry(Long id) {
		Notification n = repository.findById(id)
				.orElseThrow(() -> new NotificationNotFoundException(id));
		if (n.getStatus() != NotificationStatus.DEAD_LETTER) {
			throw new NotificationNotRetryableException(id, n.getStatus());
		}
		n.retry(clock.instant());
		log.info("notification manual-retry id={} retryCount={}", id, n.getRetryCount());
		return NotificationResponse.from(n);
	}

	/** 배치 재시도: errorCode가 null이면 전체 DEAD_LETTER 재큐잉. 재큐잉 건수 반환. */
	@Transactional
	public int batchRetry(String errorCode) {
		int count = repository.requeueDeadLetters(errorCode, clock.instant());
		log.info("notification batch-retry errorCode={} requeued={}", errorCode, count);
		return count;
	}

	@Transactional(readOnly = true)
	public List<NotificationResponse> listDeadLetters() {
		return repository.findByStatusOrderByCreatedAtDesc(NotificationStatus.DEAD_LETTER)
				.stream().map(NotificationResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public List<DeadLetterStat> deadLetterStats() {
		return repository.deadLetterStats();
	}
}
