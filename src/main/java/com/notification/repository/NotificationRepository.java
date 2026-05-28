package com.notification.repository;

import com.notification.api.dto.DeadLetterStat;
import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
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

	/**
	 * Worker 폴링: 발송 시각이 도래한 PENDING/FAILED 배치를 잠그고 가져온다.
	 * FOR UPDATE SKIP LOCKED로 다중 인스턴스가 서로 다른 행을 픽업한다(대기 없음).
	 */
	@Query(value = """
			SELECT * FROM notification
			WHERE status IN ('PENDING', 'FAILED')
			  AND (scheduled_at IS NULL OR scheduled_at <= :now)
			  AND (next_retry_at IS NULL OR next_retry_at <= :now)
			ORDER BY created_at
			LIMIT :limit
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Notification> findBatchForProcessing(@Param("now") Instant now, @Param("limit") int limit);

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

	// --- 운영자 도구 (DEAD_LETTER 모니터링 / 배치 재시도) ---

	List<Notification> findByStatusOrderByCreatedAtDesc(NotificationStatus status);

	@Query("SELECT new com.notification.api.dto.DeadLetterStat(n.lastErrorCode, COUNT(n)) "
			+ "FROM Notification n WHERE n.status = com.notification.domain.NotificationStatus.DEAD_LETTER "
			+ "GROUP BY n.lastErrorCode ORDER BY COUNT(n) DESC")
	List<DeadLetterStat> deadLetterStats();

	/**
	 * 배치 수동 재시도: DEAD_LETTER를 PENDING으로 재큐잉. errorCode가 null이면 전체.
	 * WHERE가 DEAD_LETTER만 대상으로 하므로 상태 전이 불변식(DEAD_LETTER→PENDING)을 만족한다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("UPDATE Notification n SET n.status = com.notification.domain.NotificationStatus.PENDING, "
			+ "n.nextRetryAt = :now, n.deadLetterAt = null, n.leaseExpiresAt = null, n.updatedAt = :now "
			+ "WHERE n.status = com.notification.domain.NotificationStatus.DEAD_LETTER "
			+ "AND (:errorCode IS NULL OR n.lastErrorCode = :errorCode)")
	int requeueDeadLetters(@Param("errorCode") String errorCode, @Param("now") Instant now);
}
