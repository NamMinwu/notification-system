package com.notification.exception;

public class NotificationAccessDeniedException extends RuntimeException {

	public NotificationAccessDeniedException(Long id) {
		super("해당 알림에 접근 권한이 없습니다: id=" + id);
	}
}
