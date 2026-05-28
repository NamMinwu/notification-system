package com.notification.sender;

/** 일시적 실패 — 재시도 대상 (네트워크 장애, SMTP timeout 등). */
public class SenderTransientException extends NotificationSendException {

	public SenderTransientException(String errorCode, String message) {
		super(errorCode, message);
	}
}
