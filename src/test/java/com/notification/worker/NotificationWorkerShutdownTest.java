package com.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notification.config.NotificationProperties;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 발송 풀 graceful shutdown 시퀀스 검증 (shutdown → awaitTermination → 실패 시 shutdownNow). */
@ExtendWith(MockitoExtension.class)
class NotificationWorkerShutdownTest {

	@Mock
	private NotificationProcessor processor;

	@Mock
	private NotificationProperties properties;

	@Mock
	private ExecutorService sendExecutor;

	private NotificationWorker worker() {
		return new NotificationWorker(processor, properties, sendExecutor);
	}

	@Test
	@DisplayName("정상 종료: awaitTermination이 성공하면 shutdownNow는 호출하지 않음")
	void gracefulShutdown_completesWithoutForce() throws Exception {
		when(sendExecutor.awaitTermination(anyLong(), any())).thenReturn(true);

		worker().shutdownSendExecutor();

		verify(sendExecutor).shutdown();
		verify(sendExecutor, never()).shutdownNow();
	}

	@Test
	@DisplayName("타임아웃: awaitTermination이 실패하면 shutdownNow로 강제 종료")
	void shutdown_timesOut_forcesShutdownNow() throws Exception {
		when(sendExecutor.awaitTermination(anyLong(), any())).thenReturn(false);

		worker().shutdownSendExecutor();

		verify(sendExecutor).shutdown();
		verify(sendExecutor).shutdownNow();
	}

	@Test
	@DisplayName("인터럽트: awaitTermination이 인터럽트되면 shutdownNow + 인터럽트 플래그 복원")
	void shutdown_interrupted_forcesShutdownNowAndRestoresFlag() throws Exception {
		when(sendExecutor.awaitTermination(anyLong(), any()))
				.thenThrow(new InterruptedException());

		worker().shutdownSendExecutor();

		verify(sendExecutor).shutdownNow();
		assertThat(Thread.interrupted()).isTrue(); // 플래그 복원 확인(겸 클리어)
	}
}
