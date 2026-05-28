package com.notification.service;

import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationTemplate;
import com.notification.domain.NotificationType;
import com.notification.exception.InvalidTemplateException;
import com.notification.repository.NotificationTemplateRepository;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 템플릿 조회/업서트. 조회는 단일 행 인덱스 조회라 비용이 작아 캐시를 두지 않는다(항상 최신).
 * 읽기 부하가 커지면 분산 캐시(Redis) 또는 TTL 로컬 캐시 도입 — 02-REQUIREMENTS 개선 의견 참고.
 */
@Service
@RequiredArgsConstructor
public class TemplateService {

	public static final String DEFAULT_LANGUAGE = "ko";

	private final NotificationTemplateRepository repository;
	private final Clock clock;
	private final Mustache.Compiler mustache = Mustache.compiler();

	@Transactional(readOnly = true)
	public Optional<NotificationTemplate> findActive(
			NotificationType type, NotificationChannel channel, String language) {
		return repository.findByNotificationTypeAndChannelAndLanguageAndActiveTrue(type, channel, language);
	}

	/** 운영 중 문구 수정(업서트). 활성 템플릿이 있으면 갱신, 없으면 생성. 등록 시 Mustache 문법 검증. */
	@Transactional
	public NotificationTemplate upsert(NotificationType type, NotificationChannel channel,
			String language, String subject, String body) {
		validateSyntax(subject);
		validateSyntax(body);
		Instant now = clock.instant();
		return repository
				.findByNotificationTypeAndChannelAndLanguageAndActiveTrue(type, channel, language)
				.map(existing -> {
					existing.updateContent(subject, body, now);
					return existing;
				})
				.orElseGet(() -> repository.save(NotificationTemplate.builder()
						.notificationType(type).channel(channel).language(language)
						.subject(subject).body(body).createdAt(now).build()));
	}

	private void validateSyntax(String template) {
		if (template == null) {
			return;
		}
		try {
			mustache.compile(template);
		} catch (MustacheException e) {
			throw new InvalidTemplateException(e.getMessage());
		}
	}
}
