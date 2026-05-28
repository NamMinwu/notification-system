package com.notification.service;

import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import java.util.Map;

/**
 * 템플릿 렌더링 추상화. "메시지를 어떻게 표현할 것인가"는 발송 책임자인 알림 서비스의 관심사다.
 * 현재는 알림 서비스 내부 DB 템플릿({@link DbTemplateRenderer})을 쓰지만, 향후 템플릿 전용 서비스로
 * 분리하더라도 구현체만 교체하면 된다(Sender/Dispatcher와 동일한 추상화 철학).
 */
public interface TemplateRenderer {

	RenderedMessage render(NotificationType type, NotificationChannel channel, Map<String, Object> payload);
}
