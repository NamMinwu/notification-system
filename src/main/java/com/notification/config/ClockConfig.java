package com.notification.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시각 의존성을 주입 가능하게 분리 — 테스트에서 고정 Clock으로 교체해 결정적 검증. */
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
