package com.notification.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notification.api.dto.CreateNotificationResponse;
import com.notification.api.dto.NotificationResponse;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.exception.NotificationAccessDeniedException;
import com.notification.exception.NotificationNotCancellableException;
import com.notification.exception.NotificationNotFoundException;
import com.notification.service.NotificationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private NotificationService service;

	private NotificationResponse sampleResponse(Long id) {
		return new NotificationResponse(id, "user-1", NotificationType.PAYMENT_CONFIRMED,
				NotificationChannel.EMAIL, NotificationStatus.PENDING, Map.of("amount", 5000),
				false, null, null, 0, null, Instant.parse("2026-05-28T10:00:00Z"));
	}

	@Test
	@DisplayName("POST: 신규 접수는 201 + duplicated=false")
	void create_new_returns201() throws Exception {
		when(service.create(any()))
				.thenReturn(new CreateNotificationResponse(1L, NotificationStatus.PENDING, false));

		mockMvc.perform(post("/api/v1/notifications")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"recipientId":"user-1","notificationType":"PAYMENT_CONFIRMED",
								 "channel":"EMAIL","eventId":"evt-1","payload":{"amount":5000}}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.duplicated").value(false));
	}

	@Test
	@DisplayName("POST: 멱등 중복은 200 + duplicated=true")
	void create_duplicate_returns200() throws Exception {
		when(service.create(any()))
				.thenReturn(new CreateNotificationResponse(1L, NotificationStatus.PENDING, true));

		mockMvc.perform(post("/api/v1/notifications")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"recipientId":"user-1","notificationType":"PAYMENT_CONFIRMED",
								 "channel":"EMAIL","eventId":"evt-1"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.duplicated").value(true));
	}

	@Test
	@DisplayName("POST: 필수값 누락(recipientId)은 400")
	void create_invalid_returns400() throws Exception {
		mockMvc.perform(post("/api/v1/notifications")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"recipientId":"","notificationType":"PAYMENT_CONFIRMED","channel":"EMAIL"}"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("POST: 깨진 JSON 본문은 400 (내부 파싱 상세 미노출)")
	void create_malformedJson_returns400() throws Exception {
		mockMvc.perform(post("/api/v1/notifications")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"recipientId\": "))   // 잘린/깨진 JSON
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("POST: 잘못된 enum 값은 400")
	void create_invalidEnum_returns400() throws Exception {
		mockMvc.perform(post("/api/v1/notifications")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"recipientId":"user-1","notificationType":"NOPE","channel":"EMAIL"}"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("GET /{id}: 본인이면 200 상태 반환")
	void getById_returns200() throws Exception {
		when(service.getById(1L, "user-1")).thenReturn(sampleResponse(1L));

		mockMvc.perform(get("/api/v1/notifications/1").header("X-User-Id", "user-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.recipientId").value("user-1"));
	}

	@Test
	@DisplayName("GET /{id}: 없으면 404")
	void getById_notFound_returns404() throws Exception {
		when(service.getById(999L, "user-1")).thenThrow(new NotificationNotFoundException(999L));

		mockMvc.perform(get("/api/v1/notifications/999").header("X-User-Id", "user-1"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("GET /{id}: 다른 사용자는 403 (IDOR 방지)")
	void getById_wrongUser_returns403() throws Exception {
		when(service.getById(2L, "intruder")).thenThrow(new NotificationAccessDeniedException(2L));

		mockMvc.perform(get("/api/v1/notifications/2").header("X-User-Id", "intruder"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("GET 목록: X-User-Id 본인 알림 배열 반환")
	void list_returns200() throws Exception {
		when(service.list("user-1", false)).thenReturn(List.of(sampleResponse(1L), sampleResponse(2L)));

		mockMvc.perform(get("/api/v1/notifications").header("X-User-Id", "user-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	@DisplayName("PATCH /{id}/read: X-User-Id로 읽음 처리 200")
	void markAsRead_returns200() throws Exception {
		when(service.markAsRead(eq(1L), eq("user-1"))).thenReturn(sampleResponse(1L));

		mockMvc.perform(patch("/api/v1/notifications/1/read").header("X-User-Id", "user-1"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("PATCH /{id}/read: 다른 사용자는 403")
	void markAsRead_forbidden_returns403() throws Exception {
		when(service.markAsRead(eq(1L), eq("intruder")))
				.thenThrow(new NotificationAccessDeniedException(1L));

		mockMvc.perform(patch("/api/v1/notifications/1/read").header("X-User-Id", "intruder"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("PATCH /{id}/read: X-User-Id 헤더 누락은 400")
	void markAsRead_missingHeader_returns400() throws Exception {
		mockMvc.perform(patch("/api/v1/notifications/1/read"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("PATCH /read-all: 처리 건수 반환")
	void markAllAsRead_returns200() throws Exception {
		when(service.markAllAsRead("user-1")).thenReturn(3);

		mockMvc.perform(patch("/api/v1/notifications/read-all").header("X-User-Id", "user-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.updatedCount").value(3));
	}

	@Test
	@DisplayName("PATCH /{id}/cancel: 본인 PENDING 취소 200")
	void cancel_returns200() throws Exception {
		when(service.cancel(eq(1L), eq("user-1")))
				.thenReturn(new NotificationResponse(1L, "user-1", NotificationType.PAYMENT_CONFIRMED,
						NotificationChannel.EMAIL, NotificationStatus.CANCELLED, Map.of(), false, null,
						null, 0, null, Instant.parse("2026-05-28T10:00:00Z")));

		mockMvc.perform(patch("/api/v1/notifications/1/cancel").header("X-User-Id", "user-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	@DisplayName("PATCH /{id}/cancel: PENDING 아니면 409")
	void cancel_notPending_returns409() throws Exception {
		when(service.cancel(eq(1L), eq("user-1")))
				.thenThrow(new NotificationNotCancellableException(1L, NotificationStatus.SENT));

		mockMvc.perform(patch("/api/v1/notifications/1/cancel").header("X-User-Id", "user-1"))
				.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("PATCH /{id}/cancel: X-User-Id 누락 400")
	void cancel_missingHeader_returns400() throws Exception {
		mockMvc.perform(patch("/api/v1/notifications/1/cancel"))
				.andExpect(status().isBadRequest());
	}
}
