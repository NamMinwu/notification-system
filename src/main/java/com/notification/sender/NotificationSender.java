package com.notification.sender;

import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;

/**
 * 채널별 발송 추상화. 운영 전환 시(SMTP/푸시 연동) 이 구현체만 교체하면 된다.
 * 실패 시 {@link NotificationSendException}(Transient/Permanent)을 던진다.
 */
public interface NotificationSender {

	void send(Notification notification);

	NotificationChannel channel();
}
