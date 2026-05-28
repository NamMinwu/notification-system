package com.notification.worker;

import com.notification.config.NotificationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkerConfig {

	@Bean
	public RetryPolicy retryPolicy(NotificationProperties properties) {
		return new RetryPolicy(properties.retry());
	}
}
