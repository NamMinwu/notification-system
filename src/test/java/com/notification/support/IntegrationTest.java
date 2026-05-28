package com.notification.support;

import com.notification.TestcontainersConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 통합 테스트 메타 애너테이션. Testcontainers PostgreSQL을 @ServiceConnection으로 띄우고
 * test 프로파일을 활성화한다. 컨테이너는 1개를 재사용하며, 테스트 격리는 TRUNCATE로 한다
 * ({@link DatabaseCleaner}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
public @interface IntegrationTest {
}
