package com.notification.api;

import com.notification.exception.AdminForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** 간소화된 관리자 가드: X-User-Role 헤더가 ADMIN이 아니면 차단(과제 허용 범위). */
public class AdminAuthInterceptor implements HandlerInterceptor {

	private static final String ROLE_HEADER = "X-User-Role";
	private static final String ADMIN_ROLE = "ADMIN";

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!ADMIN_ROLE.equals(request.getHeader(ROLE_HEADER))) {
			throw new AdminForbiddenException();
		}
		return true;
	}
}
