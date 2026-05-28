package com.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.api.dto.NotificationResponse;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.domain.Notification;
import com.notification.exception.NotificationAccessDeniedException;
import com.notification.exception.NotificationNotCancellableException;
import com.notification.exception.NotificationNotFoundException;
import com.notification.repository.NotificationRepository;
import com.notification.support.DatabaseCleaner;
import com.notification.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class NotificationServiceIT {

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

	private CreateNotificationRequest request(String eventId, String recipientId) {
		return new CreateNotificationRequest(
				recipientId,
				NotificationType.PAYMENT_CONFIRMED,
				NotificationChannel.EMAIL,
				eventId,
				Map.of("amount", 5000),
				null);
	}

	@Test
	@DisplayName("create: 신규 요청은 PENDING으로 접수되고 duplicated=false")
	void create_new() {
		CreateNotificationResponse res = service.create(request("evt-1", "user-1"));

		assertThat(res.id()).isNotNull();
		assertThat(res.status()).isEqualTo(NotificationStatus.PENDING);
		assertThat(res.duplicated()).isFalse();
	}

	@Test
	@DisplayName("create: 동일 (event,recipient,channel,type) 재요청은 멱등 — 같은 id, duplicated=true")
	void create_duplicate_isIdempotent() {
		CreateNotificationResponse first = service.create(request("evt-1", "user-1"));

		CreateNotificationResponse second = service.create(request("evt-1", "user-1"));

		assertThat(second.id()).isEqualTo(first.id());
		assertThat(second.duplicated()).isTrue();
	}

	@Test
	@DisplayName("create: eventId가 NULL이면 중복 검사 없이 매번 신규 생성")
	void create_nullEventId_allowsMultiple() {
		CreateNotificationResponse a = service.create(request(null, "user-1"));
		CreateNotificationResponse b = service.create(request(null, "user-1"));

		assertThat(a.id()).isNotEqualTo(b.id());
		assertThat(a.duplicated()).isFalse();
		assertThat(b.duplicated()).isFalse();
	}

	@Test
	@DisplayName("getById: 본인 알림은 반환, 없으면 NotFound")
	void getById() {
		Long id = service.create(request("evt-1", "user-1")).id();

		NotificationResponse res = service.getById(id, "user-1");
		assertThat(res.id()).isEqualTo(id);
		assertThat(res.recipientId()).isEqualTo("user-1");

		assertThatThrownBy(() -> service.getById(999_999L, "user-1"))
				.isInstanceOf(NotificationNotFoundException.class);
	}

	@Test
	@DisplayName("getById: 다른 사용자가 조회하면 AccessDenied (IDOR 방지)")
	void getById_wrongUser_denied() {
		Long id = service.create(request("evt-1", "user-1")).id();

		assertThatThrownBy(() -> service.getById(id, "intruder"))
				.isInstanceOf(NotificationAccessDeniedException.class);
	}

	@Test
	@DisplayName("list: 수신자 기준 최신순, unreadOnly 필터")
	void list_byRecipient_withUnreadFilter() {
		Long id1 = service.create(request("evt-1", "user-1")).id();
		service.create(request("evt-2", "user-1"));
		service.create(request("evt-3", "other"));

		List<NotificationResponse> all = service.list("user-1", false);
		assertThat(all).hasSize(2);

		service.markAsRead(id1, "user-1");
		List<NotificationResponse> unread = service.list("user-1", true);
		assertThat(unread).hasSize(1);
		assertThat(unread.get(0).read()).isFalse();
	}

	@Test
	@DisplayName("markAsRead: 첫 호출은 읽음 처리, 두 번째 호출은 멱등(readAt 첫 시각 유지)")
	void markAsRead_idempotent() {
		Long id = service.create(
				new CreateNotificationRequest("user-1", NotificationType.ENROLLMENT_COMPLETE,
						NotificationChannel.IN_APP, "evt-1", Map.of(), null)).id();

		NotificationResponse afterFirst = service.markAsRead(id, "user-1");
		assertThat(afterFirst.read()).isTrue();
		assertThat(afterFirst.readAt()).isNotNull();

		NotificationResponse afterSecond = service.markAsRead(id, "user-1");
		assertThat(afterSecond.readAt()).isEqualTo(afterFirst.readAt());
	}

	@Test
	@DisplayName("markAsRead: 다른 사용자가 시도하면 AccessDenied")
	void markAsRead_wrongUser_denied() {
		Long id = service.create(request("evt-1", "user-1")).id();

		assertThatThrownBy(() -> service.markAsRead(id, "intruder"))
				.isInstanceOf(NotificationAccessDeniedException.class);
	}

	@Test
	@DisplayName("markAllAsRead: 안 읽은 알림만 일괄 처리하고 건수 반환")
	void markAllAsRead() {
		service.create(request("evt-1", "user-1"));
		service.create(request("evt-2", "user-1"));

		int updated = service.markAllAsRead("user-1");
		assertThat(updated).isEqualTo(2);

		int again = service.markAllAsRead("user-1");
		assertThat(again).isZero();
	}

	@Test
	@DisplayName("cancel: 본인의 PENDING 알림은 CANCELLED")
	void cancel_pending() {
		Long id = service.create(request("evt-1", "user-1")).id();

		NotificationResponse res = service.cancel(id, "user-1");

		assertThat(res.status()).isEqualTo(NotificationStatus.CANCELLED);
	}

	@Test
	@DisplayName("cancel: 다른 사용자는 AccessDenied")
	void cancel_wrongUser_denied() {
		Long id = service.create(request("evt-1", "user-1")).id();

		assertThatThrownBy(() -> service.cancel(id, "intruder"))
				.isInstanceOf(NotificationAccessDeniedException.class);
	}

	@Test
	@DisplayName("cancel: PENDING이 아니면 NotCancellable")
	void cancel_notPending_throws() {
		Notification sent = Notification.builder()
				.recipientId("user-1").notificationType(NotificationType.PAYMENT_CONFIRMED)
				.channel(NotificationChannel.EMAIL).eventId("evt-1")
				.createdAt(Instant.parse("2026-05-28T10:00:00Z")).build();
		sent.startProcessing(Instant.parse("2026-05-28T10:00:00Z"),
				Instant.parse("2026-05-28T10:05:00Z"));
		sent.markSent(Instant.parse("2026-05-28T10:00:01Z"));
		Long id = repository.saveAndFlush(sent).getId();

		assertThatThrownBy(() -> service.cancel(id, "user-1"))
				.isInstanceOf(NotificationNotCancellableException.class);
	}
}
