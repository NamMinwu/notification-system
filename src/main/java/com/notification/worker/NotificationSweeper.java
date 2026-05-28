package com.notification.worker;

import com.notification.config.NotificationProperties;
import com.notification.repository.NotificationRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lease 기반 좀비 복구. Worker가 PROCESSING으로 잡은 뒤 죽으면(OOM/kill, 서버 재시작) 그 알림은
 * PROCESSING에 갇힌다. 주기적으로 lease가 만료된 행을 PENDING으로 되돌려 다음 폴링에서 재처리되게 한다.
 * 서버 재시작 후에도 DB에 남은 PROCESSING이 이 경로로 자동 복구된다(유실 없음).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSweeper {

	private final NotificationRepository repository;
	private final NotificationProperties properties;
	private final Clock clock;

	@Scheduled(fixedDelayString = "${notification.sweeper.interval}")
	public void scheduledSweep() {
		if (!properties.schedulingEnabled()) {
			return;
		}
		sweep();
	}

	/** 만료된 lease를 복구한다. 복구 건수 반환. 테스트에서 직접 호출해 결정적으로 검증한다. */
	public int sweep() {
		int recovered = repository.recoverExpiredLeases(clock.instant());
		if (recovered > 0) {
			log.warn("recovered {} stuck PROCESSING notifications (lease expired)", recovered);
		}
		return recovered;
	}
}
