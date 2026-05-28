package com.notification.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 배치 수동 재시도 요청. errorCode는 필수 — 실수로 전체 DEAD_LETTER를 재큐잉하는 것을 방지. */
public record BatchRetryRequest(@NotBlank String errorCode) {
}
