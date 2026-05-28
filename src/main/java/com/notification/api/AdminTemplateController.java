package com.notification.api;

import com.notification.api.dto.TemplateResponse;
import com.notification.api.dto.TemplateUpsertRequest;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import com.notification.exception.NotificationTemplateNotFoundException;
import com.notification.service.TemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 운영자 전용 템플릿 관리. X-User-Role: ADMIN 가드는 {@link AdminAuthInterceptor}에서 처리. */
@RestController
@RequestMapping("/api/v1/admin/templates")
@RequiredArgsConstructor
public class AdminTemplateController {

	private final TemplateService templateService;

	/** 운영 중 문구 업서트(생성/수정). */
	@PutMapping
	public TemplateResponse upsert(@Valid @RequestBody TemplateUpsertRequest request) {
		String language = request.language() == null
				? TemplateService.DEFAULT_LANGUAGE : request.language();
		return TemplateResponse.from(templateService.upsert(
				request.notificationType(), request.channel(), language,
				request.subject(), request.body()));
	}

	@GetMapping
	public TemplateResponse get(
			@RequestParam NotificationType notificationType,
			@RequestParam NotificationChannel channel,
			@RequestParam(required = false) String language) {
		String lang = language == null ? TemplateService.DEFAULT_LANGUAGE : language;
		return templateService.findActive(notificationType, channel, lang)
				.map(TemplateResponse::from)
				.orElseThrow(() -> new NotificationTemplateNotFoundException(notificationType, channel, lang));
	}
}
