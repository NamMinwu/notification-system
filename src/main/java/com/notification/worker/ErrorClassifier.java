package com.notification.worker;

import com.notification.sender.NotificationSendException;
import com.notification.sender.SenderPermanentException;
import org.springframework.stereotype.Component;

/** 발송 예외를 재시도 가능/영구로 분류하고 에러 코드를 추출한다. */
@Component
public class ErrorClassifier {

	/** 영구 실패(SenderPermanentException)만 재시도 제외. 그 외는 보수적으로 재시도. */
	public boolean isRetryable(Throwable t) {
		return !(t instanceof SenderPermanentException);
	}

	public String errorCode(Throwable t) {
		if (t instanceof NotificationSendException sendException) {
			return sendException.getErrorCode();
		}
		return t.getClass().getSimpleName();
	}
}
