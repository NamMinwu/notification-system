package com.notification.sender;

import com.notification.domain.Notification;

/**
 * 알림 1건을 적절한 채널 sender로 라우팅해 발송한다. 발송 메커니즘(폴링/Kafka)과 무관하게
 * "한 건을 보낸다"는 행위를 추상화하므로, 운영 전환 시에도 재사용된다.
 */
public interface NotificationDispatcher {

	void dispatch(Notification notification);
}
