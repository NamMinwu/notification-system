package com.notification.exception;

import com.notification.domain.NotificationStatus;

/** DEAD_LETTER가 아닌 알림에 수동 재시도를 시도한 경우. */
public class NotificationNotRetryableException extends RuntimeException {

	public NotificationNotRetryableException(Long id, NotificationStatus actualStatus) {
		super("DEAD_LETTER 상태만 수동 재시도할 수 있습니다: id=" + id + ", 현재 상태=" + actualStatus);
	}
}
