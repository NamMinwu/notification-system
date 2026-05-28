package com.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.sender.SenderPermanentException;
import com.notification.sender.SenderTransientException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErrorClassifierTest {

	private final ErrorClassifier classifier = new ErrorClassifier();

	@Test
	@DisplayName("일시 실패는 재시도 대상")
	void transient_isRetryable() {
		assertThat(classifier.isRetryable(new SenderTransientException("SMTP_TIMEOUT", "timeout")))
				.isTrue();
	}

	@Test
	@DisplayName("영구 실패는 재시도 대상 아님")
	void permanent_isNotRetryable() {
		assertThat(classifier.isRetryable(new SenderPermanentException("INVALID_EMAIL", "bad")))
				.isFalse();
	}

	@Test
	@DisplayName("알 수 없는 예외는 보수적으로 재시도 대상")
	void unknown_isRetryableByDefault() {
		assertThat(classifier.isRetryable(new IOException("network"))).isTrue();
	}

	@Test
	@DisplayName("errorCode: Sender 예외는 자체 코드, 그 외는 클래스명")
	void errorCode() {
		assertThat(classifier.errorCode(new SenderTransientException("SMTP_TIMEOUT", "x")))
				.isEqualTo("SMTP_TIMEOUT");
		assertThat(classifier.errorCode(new IOException("x"))).isEqualTo("IOException");
	}
}
