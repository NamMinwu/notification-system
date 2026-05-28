package com.notification.service;

import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.api.dto.NotificationResponse;
import com.notification.domain.Notification;
import com.notification.exception.NotificationAccessDeniedException;
import com.notification.exception.NotificationNotFoundException;
import com.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

	private final NotificationRepository repository;
	private final Clock clock;

	/**
	 * 알림 발송 요청 등록(Outbox INSERT). 동시성/중복은 DB UNIQUE 제약에 위임한다.
	 * 충돌 시 기존 알림을 멱등 반환(duplicated=true). {@code @Transactional}을 두지 않아
	 * saveAndFlush가 독립 트랜잭션에서 커밋/롤백되고, 충돌 후 재조회가 새 트랜잭션에서 동작한다.
	 */
	public CreateNotificationResponse create(CreateNotificationRequest req) {
		Instant now = clock.instant();
		Notification n = Notification.builder()
				.recipientId(req.recipientId())
				.notificationType(req.notificationType())
				.channel(req.channel())
				.eventId(req.eventId())
				.payload(req.payload())
				.scheduledAt(req.scheduledAt())
				.createdAt(now)
				.build();
		try {
			Notification saved = repository.saveAndFlush(n);
			return new CreateNotificationResponse(saved.getId(), saved.getStatus(), false);
		} catch (DataIntegrityViolationException conflict) {
			// 충돌을 일으킨 기존 알림을 멱등 반환. 재조회가 비는 경우는 충돌과 재조회 사이에
			// 그 행이 삭제된 경우뿐인데, 삭제는 retention(기본 비활성, 30일+ 경과분)만 수행하므로
			// 실질적으로 불가능하다. 그 희박한 anomaly는 원래 예외를 그대로 전파한다.
			Notification existing = repository
					.findByEventIdAndRecipientIdAndChannelAndNotificationType(
							req.eventId(), req.recipientId(), req.channel(), req.notificationType())
					.orElseThrow(() -> conflict);
			return new CreateNotificationResponse(existing.getId(), existing.getStatus(), true);
		}
	}

	@Transactional(readOnly = true)
	public NotificationResponse getById(Long id, String requesterId) {
		Notification n = repository.findById(id)
				.orElseThrow(() -> new NotificationNotFoundException(id));
		if (!n.getRecipientId().equals(requesterId)) {
			throw new NotificationAccessDeniedException(id);
		}
		return NotificationResponse.from(n);
	}

	/** 요청자 본인의 알림만 조회한다(수신자 = 요청자). */
	@Transactional(readOnly = true)
	public List<NotificationResponse> list(String requesterId, boolean unreadOnly) {
		List<Notification> notifications = unreadOnly
				? repository.findByRecipientIdAndReadOrderByCreatedAtDesc(requesterId, false)
				: repository.findByRecipientIdOrderByCreatedAtDesc(requesterId);
		return notifications.stream().map(NotificationResponse::from).toList();
	}

	/** 멱등 읽음 처리. 본인 알림만 가능. 두 번째 이후 호출은 0건 UPDATE이지만 동일 응답. */
	@Transactional
	public NotificationResponse markAsRead(Long id, String requesterId) {
		Notification n = repository.findById(id)
				.orElseThrow(() -> new NotificationNotFoundException(id));
		if (!n.getRecipientId().equals(requesterId)) {
			throw new NotificationAccessDeniedException(id);
		}
		repository.markAsRead(id, clock.instant());
		return repository.findById(id).map(NotificationResponse::from)
				.orElseThrow(() -> new NotificationNotFoundException(id));
	}

	@Transactional
	public int markAllAsRead(String recipientId) {
		return repository.markAllAsRead(recipientId, clock.instant());
	}
}
