package com.notification.sender;

import lombok.Getter;

/** 발송 실패의 기반 예외. errorCode는 last_error_code로 기록된다. */
@Getter
public abstract class NotificationSendException extends RuntimeException {

	private final String errorCode;

	protected NotificationSendException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}
}
