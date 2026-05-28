package com.notification.repository;

import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	/** 중복 INSERT 충돌 시 기존 알림을 멱등 반환하기 위한 조회 (eventId non-null 전제). */
	Optional<Notification> findByEventIdAndRecipientIdAndChannelAndNotificationType(
			String eventId, String recipientId, NotificationChannel channel, NotificationType notificationType);

	List<Notification> findByRecipientIdOrderByCreatedAtDesc(String recipientId);

	List<Notification> findByRecipientIdAndReadOrderByCreatedAtDesc(String recipientId, boolean read);

	/** 멱등 읽음 처리: 안 읽은 경우에만 갱신 → read_at은 첫 요청 시각으로 고정. */
	@Modifying(clearAutomatically = true)
	@Query("UPDATE Notification n SET n.read = true, n.readAt = :now, n.updatedAt = :now "
			+ "WHERE n.id = :id AND n.read = false")
	int markAsRead(@Param("id") Long id, @Param("now") Instant now);

	@Modifying(clearAutomatically = true)
	@Query("UPDATE Notification n SET n.read = true, n.readAt = :now, n.updatedAt = :now "
			+ "WHERE n.recipientId = :recipientId AND n.read = false")
	int markAllAsRead(@Param("recipientId") String recipientId, @Param("now") Instant now);
}
