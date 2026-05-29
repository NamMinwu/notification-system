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
	 * 의도적으로 제한한다. 큐 용량을 batch-size로 두어 한 배치(claim 단위)는 거부 없이 모두 수용한다
	 * (단일 인스턴스에선 CallerRuns가 트리거되지 않음). graceful shutdown은
	 * {@code NotificationWorker.@PreDestroy}에서 awaitTermination으로 처리하므로 destroyMethod는 비활성.
	 */
	@Bean(destroyMethod = "")
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
