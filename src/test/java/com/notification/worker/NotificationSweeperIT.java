package com.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.repository.NotificationRepository;
import com.notification.support.DatabaseCleaner;
import com.notification.support.IntegrationTest;
import com.notification.support.MutableClock;
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

@IntegrationTest
@Import(NotificationSweeperIT.ClockTestConfig.class)
class NotificationSweeperIT {

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
	private NotificationSweeper sweeper;

	@Autowired
	private NotificationRepository repository;

	@Autowired
	private DatabaseCleaner databaseCleaner;

	@Autowired
	private Clock clock;

	private MutableClock clock() {
		return (MutableClock) clock;
	}

	@BeforeEach
	void setUp() {
		databaseCleaner.clean();
		clock().setInstant(START);
	}

	private Notification saveProcessing(String eventId, Instant leaseExpiresAt) {
		Notification n = Notification.builder()
				.recipientId("user-1")
				.notificationType(NotificationType.PAYMENT_CONFIRMED)
				.channel(NotificationChannel.EMAIL)
				.eventId(eventId)
				.createdAt(START)
				.build();
		n.startProcessing(START, leaseExpiresAt);
		return repository.saveAndFlush(n);
	}

	private Notification reload(Long id) {
		return repository.findById(id).orElseThrow();
	}

	@Test
	@DisplayName("Lease 만료된 PROCESSING(좀비)을 PENDING으로 복구")
	void expiredLease_recoveredToPending() {
		Long id = saveProcessing("evt-1", START.plus(Duration.ofMinutes(5))).getId();
		clock().advance(Duration.ofMinutes(10)); // lease(START+5m) < now(START+10m)

		int recovered = sweeper.sweep();

		assertThat(recovered).isEqualTo(1);
		Notification n = reload(id);
		assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(n.getLeaseExpiresAt()).isNull();
		assertThat(n.getProcessingStartedAt()).isNull();
	}

	@Test
	@DisplayName("아직 유효한 Lease는 복구하지 않음")
	void activeLease_notRecovered() {
		Long id = saveProcessing("evt-1", START.plus(Duration.ofMinutes(5))).getId();
		clock().advance(Duration.ofMinutes(1)); // lease still in future

		int recovered = sweeper.sweep();

		assertThat(recovered).isZero();
		assertThat(reload(id).getStatus()).isEqualTo(NotificationStatus.PROCESSING);
	}

	@Test
	@DisplayName("PROCESSING이 아닌 알림(PENDING/SENT)은 영향 없음")
	void nonProcessing_unaffected() {
		Notification pending = repository.saveAndFlush(Notification.builder()
				.recipientId("user-1").notificationType(NotificationType.PAYMENT_CONFIRMED)
				.channel(NotificationChannel.EMAIL).eventId("evt-1").createdAt(START).build());
		Notification sent = saveProcessing("evt-2", START.plus(Duration.ofMinutes(5)));
		sent.markSent(START.plus(Duration.ofSeconds(1)));
		repository.saveAndFlush(sent);
		clock().advance(Duration.ofMinutes(10));

		int recovered = sweeper.sweep();

		assertThat(recovered).isZero();
		assertThat(reload(pending.getId()).getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(reload(sent.getId()).getStatus()).isEqualTo(NotificationStatus.SENT);
	}
}
