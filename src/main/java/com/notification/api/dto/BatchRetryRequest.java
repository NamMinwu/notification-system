package com.notification.api.dto;

/** 배치 수동 재시도 요청. errorCode가 null이면 모든 DEAD_LETTER를 재큐잉한다. */
public record BatchRetryRequest(String errorCode) {
}
