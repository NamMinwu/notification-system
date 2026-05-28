package com.notification.domain;

/**
 * 알림 처리 상태 머신 (6-state).
 *
 * <pre>
 * PENDING ──▶ PROCESSING ──▶ SENT
 *    │            │   ╲──▶ FAILED ──▶ PROCESSING (재시도)
 *    │            │            ╲─────▶ DEAD_LETTER
 *    │            └──▶ PENDING (Lease 만료 좀비 복구)
 *    └──▶ CANCELLED (예약 취소)
 * DEAD_LETTER ──▶ PENDING (수동 재시도 재큐잉 — Worker는 PENDING/FAILED만 claim하므로)
 * </pre>
 */
public enum NotificationStatus {
	PENDING,
	PROCESSING,
	SENT,
	FAILED,
	DEAD_LETTER,
	CANCELLED;

	public boolean canTransitionTo(NotificationStatus next) {
		return switch (this) {
			case PENDING -> next == PROCESSING || next == CANCELLED;
			case PROCESSING -> next == SENT
					|| next == FAILED
					|| next == DEAD_LETTER
					|| next == PENDING; // 좀비 복구
			case FAILED -> next == PROCESSING || next == DEAD_LETTER;
			case DEAD_LETTER -> next == PENDING; // 수동 재시도 재큐잉 (워커가 다시 claim)
			case SENT, CANCELLED -> false; // 종료 상태
		};
	}

	public boolean isTerminal() {
		return this == SENT || this == CANCELLED;
	}
}
