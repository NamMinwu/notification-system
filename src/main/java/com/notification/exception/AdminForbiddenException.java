package com.notification.exception;

/** 관리자 권한이 없는 요청. */
public class AdminForbiddenException extends RuntimeException {

	public AdminForbiddenException() {
		super("관리자 권한이 필요합니다.");
	}
}
