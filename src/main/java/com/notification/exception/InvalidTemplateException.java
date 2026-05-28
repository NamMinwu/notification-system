package com.notification.exception;

/** 업서트한 템플릿의 Mustache 문법이 올바르지 않은 경우. */
public class InvalidTemplateException extends RuntimeException {

	public InvalidTemplateException(String detail) {
		super("템플릿 문법이 올바르지 않습니다: " + detail);
	}
}
