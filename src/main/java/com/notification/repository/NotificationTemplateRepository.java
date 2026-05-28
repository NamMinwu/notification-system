package com.notification.repository;

import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationTemplate;
import com.notification.domain.NotificationType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

	Optional<NotificationTemplate> findByNotificationTypeAndChannelAndLanguageAndActiveTrue(
			NotificationType notificationType, NotificationChannel channel, String language);
}
