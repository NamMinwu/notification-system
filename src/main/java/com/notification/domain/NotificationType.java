package com.notification.domain;

/**
 * 알림 타입. 값 객체로만 사용하며 비즈니스 분기 로직을 두지 않는다.
 * 새 타입 추가는 enum 값 + 템플릿 등록만으로 동작한다.
 */
public enum NotificationType {
	ENROLLMENT_COMPLETE,
	PAYMENT_CONFIRMED,
	CLASS_REMINDER_D1,
	CANCELLATION_PROCESSED
}
