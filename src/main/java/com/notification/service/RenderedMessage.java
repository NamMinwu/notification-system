package com.notification.service;

/** 템플릿 렌더링 결과. IN_APP은 subject를 title로, EMAIL은 제목으로 사용. */
public record RenderedMessage(String subject, String body) {
}
