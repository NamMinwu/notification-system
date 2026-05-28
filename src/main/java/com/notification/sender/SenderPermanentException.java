package com.notification.sender;

/** 영구적 실패 — 재시도해도 실패 (잘못된 주소, 구독 해지 등). 즉시 DEAD_LETTER. */
public class SenderPermanentException extends NotificationSendException {

	public SenderPermanentException(String errorCode, String message) {
		super(errorCode, message);
	}
}
