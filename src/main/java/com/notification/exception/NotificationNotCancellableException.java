package com.notification.exception;

import com.notification.domain.NotificationStatus;

/** PENDING이 아닌 알림에 예약 취소를 시도한 경우(이미 처리 중/완료 등). */
public class NotificationNotCancellableException extends RuntimeException {

	public NotificationNotCancellableException(Long id, NotificationStatus actualStatus) {
		super("PENDING 상태만 취소할 수 있습니다: id=" + id + ", 현재 상태=" + actualStatus);
	}
}
