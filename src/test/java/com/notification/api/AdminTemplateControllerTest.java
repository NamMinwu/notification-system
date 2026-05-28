package com.notification.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notification.config.WebConfig;
import com.notification.exception.InvalidTemplateException;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationTemplate;
import com.notification.domain.NotificationType;
import com.notification.service.TemplateService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminTemplateController.class)
@Import(WebConfig.class)
class AdminTemplateControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TemplateService templateService;

	private NotificationTemplate template() {
		return NotificationTemplate.builder()
				.notificationType(NotificationType.PAYMENT_CONFIRMED)
				.channel(NotificationChannel.EMAIL)
				.language("ko")
				.subject("제목")
				.body("본문")
				.createdAt(Instant.parse("2026-05-28T10:00:00Z"))
				.build();
	}

	@Test
	@DisplayName("PUT 업서트: ADMIN이면 200")
	void upsert_admin_returns200() throws Exception {
		when(templateService.upsert(any(), any(), any(), any(), any())).thenReturn(template());

		mockMvc.perform(put("/api/v1/admin/templates")
						.header("X-User-Role", "ADMIN")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"notificationType":"PAYMENT_CONFIRMED","channel":"EMAIL",
								 "subject":"제목","body":"본문"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.body").value("본문"));
	}

	@Test
	@DisplayName("PUT 업서트: body 누락이면 400")
	void upsert_missingBody_returns400() throws Exception {
		mockMvc.perform(put("/api/v1/admin/templates")
						.header("X-User-Role", "ADMIN")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"notificationType":"PAYMENT_CONFIRMED","channel":"EMAIL"}"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("PUT 업서트: 관리자 아니면 403")
	void upsert_nonAdmin_returns403() throws Exception {
		mockMvc.perform(put("/api/v1/admin/templates")
						.header("X-User-Role", "USER")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"notificationType":"PAYMENT_CONFIRMED","channel":"EMAIL","body":"본문"}"""))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("GET: 활성 템플릿 조회 200")
	void get_returns200() throws Exception {
		when(templateService.findActive(
				eq(NotificationType.PAYMENT_CONFIRMED), eq(NotificationChannel.EMAIL), eq("ko")))
				.thenReturn(Optional.of(template()));

		mockMvc.perform(get("/api/v1/admin/templates")
						.header("X-User-Role", "ADMIN")
						.param("notificationType", "PAYMENT_CONFIRMED")
						.param("channel", "EMAIL"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.body").value("본문"));
	}

	@Test
	@DisplayName("GET: 템플릿 없으면 404")
	void get_notFound_returns404() throws Exception {
		when(templateService.findActive(any(), any(), any())).thenReturn(Optional.empty());

		mockMvc.perform(get("/api/v1/admin/templates")
						.header("X-User-Role", "ADMIN")
						.param("notificationType", "PAYMENT_CONFIRMED")
						.param("channel", "EMAIL"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("GET: 관리자 아니면 403")
	void get_nonAdmin_returns403() throws Exception {
		mockMvc.perform(get("/api/v1/admin/templates")
						.header("X-User-Role", "USER")
						.param("notificationType", "PAYMENT_CONFIRMED")
						.param("channel", "EMAIL"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("PUT 업서트: 잘못된 Mustache 문법이면 400")
	void upsert_invalidTemplate_returns400() throws Exception {
		when(templateService.upsert(any(), any(), any(), any(), any()))
				.thenThrow(new InvalidTemplateException("unclosed tag"));

		mockMvc.perform(put("/api/v1/admin/templates")
						.header("X-User-Role", "ADMIN")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"notificationType":"PAYMENT_CONFIRMED","channel":"EMAIL","body":"{{a"}"""))
				.andExpect(status().isBadRequest());
	}
}
