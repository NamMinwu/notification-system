package com.notification.api;

import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.api.dto.NotificationResponse;
import com.notification.service.NotificationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

	private final NotificationService service;

	/** 발송 요청 접수. 신규는 201, 멱등 중복은 200. */
	@PostMapping
	public ResponseEntity<CreateNotificationResponse> create(
			@Valid @RequestBody CreateNotificationRequest request) {
		CreateNotificationResponse response = service.create(request);
		HttpStatus status = response.duplicated() ? HttpStatus.OK : HttpStatus.CREATED;
		return ResponseEntity.status(status).body(response);
	}

	@GetMapping("/{id}")
	public NotificationResponse getById(
			@PathVariable Long id,
			@RequestHeader("X-User-Id") String userId) {
		return service.getById(id, userId);
	}

	/** 요청자(X-User-Id) 본인의 알림 목록만 조회. */
	@GetMapping
	public List<NotificationResponse> list(
			@RequestHeader("X-User-Id") String userId,
			@RequestParam(defaultValue = "false") boolean unreadOnly) {
		return service.list(userId, unreadOnly);
	}

	@PatchMapping("/{id}/read")
	public NotificationResponse markAsRead(
			@PathVariable Long id,
			@RequestHeader("X-User-Id") String userId) {
		return service.markAsRead(id, userId);
	}

	@PatchMapping("/read-all")
	public Map<String, Integer> markAllAsRead(@RequestHeader("X-User-Id") String userId) {
		return Map.of("updatedCount", service.markAllAsRead(userId));
	}

	/** 예약 발송 취소 (PENDING만). */
	@PatchMapping("/{id}/cancel")
	public NotificationResponse cancel(
			@PathVariable Long id,
			@RequestHeader("X-User-Id") String userId) {
		return service.cancel(id, userId);
	}
}
