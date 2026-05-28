package com.notification.sender;

import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 채널별 sender 레지스트리. 등록된 NotificationSender들을 채널로 매핑한다. */
@Component
public class ChannelNotificationDispatcher implements NotificationDispatcher {

	private final Map<NotificationChannel, NotificationSender> senders;

	public ChannelNotificationDispatcher(List<NotificationSender> senderList) {
		this.senders = new EnumMap<>(NotificationChannel.class);
		for (NotificationSender sender : senderList) {
			this.senders.put(sender.channel(), sender);
		}
	}

	@Override
	public void dispatch(Notification notification) {
		NotificationSender sender = senders.get(notification.getChannel());
		if (sender == null) {
			throw new IllegalStateException("채널에 대한 sender가 없습니다: " + notification.getChannel());
		}
		sender.send(notification);
	}
}
