package com.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NotificationStatusTest {

	@Nested
	@DisplayName("PENDING은 PROCESSING 또는 CANCELLED로만 전이 가능")
	class FromPending {
		@Test
		void allowsProcessingAndCancelled() {
			assertThat(NotificationStatus.PENDING.canTransitionTo(NotificationStatus.PROCESSING)).isTrue();
			assertThat(NotificationStatus.PENDING.canTransitionTo(NotificationStatus.CANCELLED)).isTrue();
		}

		@Test
		void rejectsOthers() {
			assertThat(NotificationStatus.PENDING.canTransitionTo(NotificationStatus.SENT)).isFalse();
			assertThat(NotificationStatus.PENDING.canTransitionTo(NotificationStatus.FAILED)).isFalse();
			assertThat(NotificationStatus.PENDING.canTransitionTo(NotificationStatus.DEAD_LETTER)).isFalse();
		}
	}

	@Nested
	@DisplayName("PROCESSING은 SENT/FAILED/DEAD_LETTER/PENDING(좀비 복구)로 전이 가능")
	class FromProcessing {
		@Test
		void allowsTerminalRetryAndZombieRecovery() {
			assertThat(NotificationStatus.PROCESSING.canTransitionTo(NotificationStatus.SENT)).isTrue();
			assertThat(NotificationStatus.PROCESSING.canTransitionTo(NotificationStatus.FAILED)).isTrue();
			assertThat(NotificationStatus.PROCESSING.canTransitionTo(NotificationStatus.DEAD_LETTER)).isTrue();
			assertThat(NotificationStatus.PROCESSING.canTransitionTo(NotificationStatus.PENDING)).isTrue();
		}

		@Test
		void rejectsCancelled() {
			assertThat(NotificationStatus.PROCESSING.canTransitionTo(NotificationStatus.CANCELLED)).isFalse();
		}
	}

	@Nested
	@DisplayName("FAILED는 PROCESSING(재시도) 또는 DEAD_LETTER로 전이 가능")
	class FromFailed {
		@Test
		void allowsRetryAndDeadLetter() {
			assertThat(NotificationStatus.FAILED.canTransitionTo(NotificationStatus.PROCESSING)).isTrue();
			assertThat(NotificationStatus.FAILED.canTransitionTo(NotificationStatus.DEAD_LETTER)).isTrue();
		}

		@Test
		void rejectsOthers() {
			assertThat(NotificationStatus.FAILED.canTransitionTo(NotificationStatus.SENT)).isFalse();
			assertThat(NotificationStatus.FAILED.canTransitionTo(NotificationStatus.PENDING)).isFalse();
			assertThat(NotificationStatus.FAILED.canTransitionTo(NotificationStatus.CANCELLED)).isFalse();
		}
	}

	@Nested
	@DisplayName("DEAD_LETTER는 수동 재시도(PROCESSING)로만 전이 가능")
	class FromDeadLetter {
		@Test
		void allowsManualRetryOnly() {
			assertThat(NotificationStatus.DEAD_LETTER.canTransitionTo(NotificationStatus.PROCESSING)).isTrue();
			assertThat(NotificationStatus.DEAD_LETTER.canTransitionTo(NotificationStatus.PENDING)).isFalse();
			assertThat(NotificationStatus.DEAD_LETTER.canTransitionTo(NotificationStatus.SENT)).isFalse();
		}
	}

	@Nested
	@DisplayName("종료 상태(SENT, CANCELLED)는 어떤 전이도 불가")
	class TerminalStates {
		@Test
		void sentIsTerminal() {
			for (NotificationStatus next : NotificationStatus.values()) {
				assertThat(NotificationStatus.SENT.canTransitionTo(next)).isFalse();
			}
		}

		@Test
		void cancelledIsTerminal() {
			for (NotificationStatus next : NotificationStatus.values()) {
				assertThat(NotificationStatus.CANCELLED.canTransitionTo(next)).isFalse();
			}
		}
	}

	@Test
	@DisplayName("terminal 여부를 정확히 보고한다")
	void isTerminal() {
		Set<NotificationStatus> terminal = EnumSet.of(NotificationStatus.SENT, NotificationStatus.CANCELLED);
		for (NotificationStatus s : NotificationStatus.values()) {
			assertThat(s.isTerminal()).isEqualTo(terminal.contains(s));
		}
	}
}
