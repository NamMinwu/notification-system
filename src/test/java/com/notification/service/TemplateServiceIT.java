package com.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import com.notification.exception.InvalidTemplateException;
import com.notification.support.DatabaseCleaner;
import com.notification.support.IntegrationTest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class TemplateServiceIT {

	@Autowired
	private TemplateService templateService;

	@Autowired
	private NotificationRenderer renderer;

	@Autowired
	private DatabaseCleaner databaseCleaner;

	@BeforeEach
	void setUp() {
		databaseCleaner.clean();
	}

	private Notification notification(NotificationType type, NotificationChannel channel,
			Map<String, Object> payload) {
		return Notification.builder()
				.recipientId("user-1")
				.notificationType(type)
				.channel(channel)
				.eventId("evt-1")
				.payload(payload)
				.createdAt(Instant.parse("2026-05-28T10:00:00Z"))
				.build();
	}

	@Test
	@DisplayName("upsert: 신규 생성 후 findActive로 조회")
	void upsert_creates() {
		templateService.upsert(NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL, "ko",
				"제목", "본문");

		assertThat(templateService.findActive(
				NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL, "ko"))
				.isPresent()
				.get()
				.satisfies(t -> assertThat(t.getBody()).isEqualTo("본문"));
	}

	@Test
	@DisplayName("upsert: 재업서트는 기존 활성 템플릿 내용을 갱신(항상 최신 조회)")
	void upsert_updatesExisting() {
		templateService.upsert(NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL, "ko",
				"v1", "본문 A");
		assertThat(templateService.findActive(
				NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL, "ko")
				.orElseThrow().getBody()).isEqualTo("본문 A");

		templateService.upsert(NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL, "ko",
				"v1", "본문 B");

		assertThat(templateService.findActive(
				NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL, "ko")
				.orElseThrow().getBody()).isEqualTo("본문 B");
	}

	@Test
	@DisplayName("render: payload 변수를 치환")
	void render_substitutesPayload() {
		templateService.upsert(NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL, "ko",
				"{{userName}}님 결제", "{{userName}}님 {{amount}}원 결제 완료");

		RenderedMessage msg = renderer.render(notification(
				NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL,
				Map.of("userName", "홍길동", "amount", 5000)));

		assertThat(msg.subject()).isEqualTo("홍길동님 결제");
		assertThat(msg.body()).isEqualTo("홍길동님 5000원 결제 완료");
	}

	@Test
	@DisplayName("render: 누락된 변수는 빈 문자열로 렌더링(실패 아님)")
	void render_missingVariable_rendersEmpty() {
		templateService.upsert(NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL, "ko",
				null, "{{a}}-{{b}}");

		RenderedMessage msg = renderer.render(notification(
				NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL, Map.of("a", "X")));

		assertThat(msg.body()).isEqualTo("X-");
	}

	@Test
	@DisplayName("render: 템플릿이 없으면 타입명 fallback")
	void render_noTemplate_fallback() {
		RenderedMessage msg = renderer.render(notification(
				NotificationType.CLASS_REMINDER_D1, NotificationChannel.IN_APP, Map.of()));

		assertThat(msg.body()).isEqualTo("CLASS_REMINDER_D1");
	}

	@Test
	@DisplayName("upsert: 잘못된 Mustache 문법(섹션 미닫힘)은 거부(InvalidTemplate)")
	void upsert_invalidSyntax_throws() {
		assertThatThrownBy(() -> templateService.upsert(
				NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL, "ko", null, "{{#x}}섹션 미닫힘"))
				.isInstanceOf(InvalidTemplateException.class);
	}
}
