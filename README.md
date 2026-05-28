# notification-system

이벤트 기반 비동기 알림 발송 시스템 (Spring Boot · JPA · PostgreSQL)

수강 신청 완료, 결제 확정, 강의 시작 D-1, 취소 처리 등 다양한 이벤트에 대해
이메일 / 인앱 알림을 **비동기**로 발송하며, 실패 시 **재시도**와 **중복 방지**,
**다중 인스턴스 안전성**을 보장하는 것을 목표로 한다.

## 문서

| 문서 | 내용 |
|---|---|
| [docs/00-DECISIONS.md](docs/00-DECISIONS.md) | 선결정 사항 (ADR) — 모든 설계 결정과 근거 |
| [docs/01-ASYNC-RETRY.md](docs/01-ASYNC-RETRY.md) | 비동기 처리 구조 및 재시도 정책 설명 |
| [docs/02-REQUIREMENTS-INTERPRETATION.md](docs/02-REQUIREMENTS-INTERPRETATION.md) | 요구사항 해석 및 개선 의견 |

## 기술 스택

- Java 21, Spring Boot 3.3.x
- Spring Data JPA (Hibernate), PostgreSQL 16, Flyway
- Gradle (Kotlin DSL)
- 테스트: JUnit 5, Testcontainers, AssertJ, Mockito, JaCoCo

## 상태

🚧 설계 문서 작성 단계. 구현 진행 중.
