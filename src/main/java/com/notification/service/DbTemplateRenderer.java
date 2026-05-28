package com.notification.service;

import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import com.samskivert.mustache.Mustache;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 알림 서비스 내부 DB 템플릿 기반 렌더러. Mustache(logic-less)로 치환하며 누락 변수는 빈 문자열로
 * 처리한다(발송 실패를 유발하지 않음). 활성 템플릿이 없으면 타입명 기반 fallback.
 */
@Component
@RequiredArgsConstructor
public class DbTemplateRenderer implements TemplateRenderer {

	private final TemplateService templateService;
	private final Mustache.Compiler mustache = Mustache.compiler().defaultValue("");

	@Override
	public RenderedMessage render(
			NotificationType type, NotificationChannel channel, Map<String, Object> payload) {
		Map<String, Object> context = payload == null ? Map.of() : payload;
		return templateService.findActive(type, channel, TemplateService.DEFAULT_LANGUAGE)
				.map(template -> new RenderedMessage(
						template.getSubject() == null
								? null
								: mustache.compile(template.getSubject()).execute(context),
						mustache.compile(template.getBody()).execute(context)))
				.orElseGet(() -> new RenderedMessage(type.name(), type.name()));
	}
}
