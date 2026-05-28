package com.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.support.DatabaseCleaner;
import com.notification.support.IntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@IntegrationTest
class NotificationRepositoryIT {

	private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");

	@Autowired
	private NotificationRepository repository;

	@Autowired
	private DatabaseCleaner databaseCleaner;

	@BeforeEach
	void setUp() {
		databaseCleaner.clean();
	}

	private Notification.Builder base() {
		return Notification.builder()
				.recipientId("user-1")
				.notificationType(NotificationType.PAYMENT_CONFIRMED)
				.channel(NotificationChannel.EMAIL)
				.eventId("evt-1")
				.createdAt(NOW);
	}

	@Test
	@DisplayName("save 후 findById로 조회 — JSONB payload 왕복")
	void saveAndFind_roundTripsJsonb() {
		Notification saved = repository.save(
				base().payload(Map.of("amount", 5000, "method", "CARD")).build());

		Optional<Notification> found = repository.findById(saved.getId());

		assertThat(found).isPresent();
		Notification n = found.orElseThrow();
		assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(n.getRecipientId()).isEqualTo("user-1");
		assertThat(n.getPayload()).containsEntry("method", "CARD");
		assertThat(n.getPayload()).containsKey("amount");
	}

	@Test
	@DisplayName("동일 (event_id, recipient, channel, type) 중복 저장은 UNIQUE 제약 위반")
	void duplicateIdempotencyKey_violatesUnique() {
		repository.saveAndFlush(base().build());

		assertThatThrownBy(() -> repository.saveAndFlush(base().build()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("event_id가 NULL이면 동일 조합도 중복으로 보지 않음 (관리자 단발 발송 허용)")
	void nullEventId_allowsMultiple() {
		repository.saveAndFlush(base().eventId(null).build());
		repository.saveAndFlush(base().eventId(null).build());

		assertThat(repository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("채널이 다르면 다른 알림으로 저장된다")
	void differentChannel_isDistinct() {
		repository.saveAndFlush(base().channel(NotificationChannel.EMAIL).build());
		repository.saveAndFlush(base().channel(NotificationChannel.IN_APP).build());

		assertThat(repository.count()).isEqualTo(2);
	}
}
