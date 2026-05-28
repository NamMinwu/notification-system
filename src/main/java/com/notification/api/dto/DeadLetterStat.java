package com.notification.api.dto;

/** DEAD_LETTER 모니터링: 에러 코드별 누적 건수. */
public record DeadLetterStat(String errorCode, long count) {
}
