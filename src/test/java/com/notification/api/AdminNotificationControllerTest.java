package com.notification.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notification.api.dto.NotificationResponse;
import com.notification.config.WebConfig;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.exception.NotificationNotRetryableException;
import com.notification.service.AdminNotificationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminNotificationController.class)
@Import(WebConfig.class)
class AdminNotificationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AdminNotificationService service;

	private NotificationResponse sample(Long id, NotificationStatus status) {
		return new NotificationResponse(id, "user-1", NotificationType.PAYMENT_CONFIRMED,
				NotificationChannel.EMAIL, status, Map.of(), false, null, null, 4, "SMTP_TIMEOUT",
				Instant.parse("2026-05-28T10:00:00Z"));
	}

	@Test
	@DisplayName("retry: ADMIN 역할이면 200")
	void retry_admin_returns200() throws Exception {
		when(service.manualRetry(1L)).thenReturn(sample(1L, NotificationStatus.PENDING));

		mockMvc.perform(post("/api/v1/admin/notifications/1/retry").header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING"));
	}

	@Test
	@DisplayName("retry: 관리자 아니면 403")
	void retry_nonAdmin_returns403() throws Exception {
		mockMvc.perform(post("/api/v1/admin/notifications/1/retry").header("X-User-Role", "USER"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("retry: 역할 헤더 없으면 403")
	void retry_noRole_returns403() throws Exception {
		mockMvc.perform(post("/api/v1/admin/notifications/1/retry"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("retry: DEAD_LETTER가 아니면 409")
	void retry_notRetryable_returns409() throws Exception {
		when(service.manualRetry(1L))
				.thenThrow(new NotificationNotRetryableException(1L, NotificationStatus.SENT));

		mockMvc.perform(post("/api/v1/admin/notifications/1/retry").header("X-User-Role", "ADMIN"))
				.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("retry: 소문자 'admin' 역할은 거부 (대소문자 구분) 403")
	void retry_lowercaseRole_returns403() throws Exception {
		mockMvc.perform(post("/api/v1/admin/notifications/1/retry").header("X-User-Role", "admin"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("retry-batch: ADMIN이면 재시도 건수 반환")
	void retryBatch_admin_returnsCount() throws Exception {
		when(service.batchRetry(any())).thenReturn(3);

		mockMvc.perform(post("/api/v1/admin/notifications/retry-batch")
						.header("X-User-Role", "ADMIN")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"errorCode\":\"SMTP_TIMEOUT\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.retriedCount").value(3));
	}

	@Test
	@DisplayName("retry-batch: errorCode 누락이면 400 (전체 재시도 방지)")
	void retryBatch_missingErrorCode_returns400() throws Exception {
		mockMvc.perform(post("/api/v1/admin/notifications/retry-batch")
						.header("X-User-Role", "ADMIN")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("dead-letter 목록: ADMIN이면 200")
	void deadLetters_admin_returns200() throws Exception {
		when(service.listDeadLetters())
				.thenReturn(List.of(sample(1L, NotificationStatus.DEAD_LETTER)));

		mockMvc.perform(get("/api/v1/admin/notifications/dead-letter").header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	@DisplayName("dead-letter 목록: 관리자 아니면 403")
	void deadLetters_nonAdmin_returns403() throws Exception {
		mockMvc.perform(get("/api/v1/admin/notifications/dead-letter"))
				.andExpect(status().isForbidden());
	}
}
