package com.notification.service;

import com.notification.domain.Notification;
import com.samskivert.mustache.Mustache;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 알림을 활성 템플릿으로 렌더링. Mustache(logic-less)로 치환하며 누락 변수는 빈 문자열로 처리한다
 * (발송 실패를 유발하지 않음). 템플릿이 없으면 타입명 기반 fallback.
 */
@Component
@RequiredArgsConstructor
public class NotificationRenderer {

	private final TemplateService templateService;
	private final Mustache.Compiler mustache = Mustache.compiler().defaultValue("");

	public RenderedMessage render(Notification notification) {
		Map<String, Object> context =
				notification.getPayload() == null ? Map.of() : notification.getPayload();
		return templateService.findActive(
						notification.getNotificationType(), notification.getChannel(),
						TemplateService.DEFAULT_LANGUAGE)
				.map(template -> new RenderedMessage(
						template.getSubject() == null
								? null
								: mustache.compile(template.getSubject()).execute(context),
						mustache.compile(template.getBody()).execute(context)))
				.orElseGet(() -> fallback(notification));
	}

	private RenderedMessage fallback(Notification notification) {
		String text = notification.getNotificationType().name();
		return new RenderedMessage(text, text);
	}
}
