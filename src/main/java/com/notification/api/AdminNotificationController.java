package com.notification.api;

import com.notification.api.dto.BatchRetryRequest;
import com.notification.api.dto.DeadLetterStat;
import com.notification.api.dto.NotificationResponse;
import com.notification.service.AdminNotificationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 운영자 전용. X-User-Role: ADMIN 가드는 {@link AdminAuthInterceptor}에서 처리. */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

	private final AdminNotificationService service;

	@PostMapping("/{id}/retry")
	public NotificationResponse retry(@PathVariable Long id) {
		return service.manualRetry(id);
	}

	@PostMapping("/retry-batch")
	public Map<String, Integer> retryBatch(@Valid @RequestBody BatchRetryRequest request) {
		return Map.of("retriedCount", service.batchRetry(request.errorCode()));
	}

	@GetMapping("/dead-letter")
	public List<NotificationResponse> deadLetters() {
		return service.listDeadLetters();
	}

	@GetMapping("/dead-letter/stats")
	public List<DeadLetterStat> deadLetterStats() {
		return service.deadLetterStats();
	}
}
