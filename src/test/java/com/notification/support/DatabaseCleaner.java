package com.notification.support;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 테스트 격리: 각 테스트 전후로 테이블을 TRUNCATE (컨테이너 1개 재사용 전략). */
@Component
@RequiredArgsConstructor
public class DatabaseCleaner {

	private final JdbcTemplate jdbcTemplate;

	public void clean() {
		jdbcTemplate.execute(
				"TRUNCATE TABLE notification, notification_template RESTART IDENTITY CASCADE");
	}
}
