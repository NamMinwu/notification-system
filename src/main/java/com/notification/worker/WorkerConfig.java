package com.notification.worker;

import com.notification.config.NotificationProperties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkerConfig {

	@Bean
	public RetryPolicy retryPolicy(NotificationProperties properties) {
		return new RetryPolicy(properties.retry());
	}

	/**
	 * 발송 전용 고정 스레드풀. 동시 발송 수 = {@code worker.concurrency}로 외부 자원 한도에 맞춰
	 * 의도적으로 제한한다. 큐가 차면 CallerRuns로 호출 스레드가 처리해 자연스러운 backpressure를 준다.
	 * destroyMethod=shutdown으로 종료 시 진행 중 작업을 마치게 한다(graceful).
	 */
	@Bean(destroyMethod = "shutdown")
	public ExecutorService sendExecutor(NotificationProperties properties) {
		int concurrency = properties.worker().concurrency();
		int queueCapacity = properties.polling().batchSize();
		return new ThreadPoolExecutor(
				concurrency, concurrency,
				0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity),
				Thread.ofPlatform().name("notif-send-", 0).factory(),
				new ThreadPoolExecutor.CallerRunsPolicy());
	}
}
