# notification-system

이벤트 기반 **비동기 알림 발송 시스템** (Spring Boot · JPA · PostgreSQL)

수강 신청 완료, 결제 확정, 강의 시작 D-1, 취소 처리 등 다양한 이벤트에 대해 이메일 / 인앱 알림을
**비동기**로 발송한다. 메시지 브로커 없이 **Transactional Outbox + Polling Worker** 패턴으로
실패 시 **재시도**, **중복 방지**, **다중 인스턴스 안전성**, **재시작 무손실**을 보장한다.

---

## 빠른 시작

### 사전 요구사항
- JDK 21
- Docker (PostgreSQL 16 구동용)

### 실행

```bash
# 1) PostgreSQL 기동 (docker-compose)
docker compose up -d

# 2) 애플리케이션 실행 (Flyway가 스키마 생성 + 템플릿 시드)
./gradlew bootRun
```

앱은 `http://localhost:8080` 에서 뜨고, 백그라운드 Worker(폴링)와 Sweeper(좀비 복구)가 자동 동작한다.
DB 접속 정보는 `application.yml` 기본값(또는 `SPRING_DATASOURCE_*` 환경변수)으로 주입된다.

### 테스트

```bash
./gradlew check     # 전체 테스트(114) + JaCoCo 커버리지 게이트(line 80% / branch 70%)
```

테스트는 **Testcontainers**로 실제 PostgreSQL 컨테이너를 띄워 통합 검증한다(Docker 필요).
시간 의존 로직은 `Clock` 주입으로 결정적으로 검증한다.

---

## 아키텍처 한눈에

```
       [비즈니스 트랜잭션]
              │ 알림 INSERT (동일 트랜잭션 = Outbox)
              ▼
   ┌─────────────────────┐   @Scheduled 폴링(5s)        ┌──────────────┐
   │  notification (DB)   │ ◀───────────────────────────│  Worker      │
   │  status = PENDING    │   FOR UPDATE SKIP LOCKED     │  (고정 풀 16) │
   └─────────────────────┘   배치 claim(50) → PROCESSING └──────┬───────┘
              ▲                                                  │ 건별 @Transactional
              │ Lease 만료 복구                                   ▼ 발송(트랜잭션 내)
   ┌──────────┴──────────┐                              ┌──────────────┐
   │  Sweeper (1m)       │   PROCESSING 좀비 → PENDING    │ Dispatcher → │
   └─────────────────────┘                              │   Sender     │
                                                        └──────┬───────┘
                                       성공 SENT / 일시실패 FAILED(백오프) / 영구·소진 DEAD_LETTER
```

- **브로커 없이 전환 가능**: `NotificationDispatcher` 인터페이스 → 향후 Kafka Consumer 구현체로 교체.
- **다중 인스턴스**: `SELECT ... FOR UPDATE SKIP LOCKED` 로 인스턴스끼리 서로 다른 행을 픽업(대기 없음, 중복 처리 없음).
- **재시작 무손실**: DB가 큐 역할. PROCESSING 상태로 죽은 건은 Lease 만료 후 Sweeper가 PENDING 복구.

### 상태 머신 (6-state)

```
PENDING ──▶ PROCESSING ──▶ SENT
   │            │   ╲──▶ FAILED ──▶ PROCESSING (백오프 후 재시도)
   │            │            ╲────▶ DEAD_LETTER (영구 실패 / 재시도 소진)
   │            └──▶ PENDING (Lease 만료 좀비 복구)
   └──▶ CANCELLED (예약 발송 취소, PENDING에서만)

DEAD_LETTER ──▶ PENDING (수동 재시도 재큐잉)
```

전이는 `NotificationStatus.canTransitionTo(next)` 로 도메인 레벨에서 강제한다.

---

## API

요청자 식별은 `X-User-Id` 헤더(과제 허용 간소화). 운영자 API는 `X-User-Role: ADMIN` 가드.

### 사용자 API — `/api/v1/notifications`

| 메서드 | 경로 | 설명 | 인증 |
|---|---|---|---|
| `POST` | `/` | 발송 요청 접수 (신규 **201**, 멱등 중복 **200**) | — |
| `GET` | `/{id}` | 알림 상태 조회 (본인만, 타인 **403**) | `X-User-Id` |
| `GET` | `/?unreadOnly=false` | 본인 알림 목록 (읽음/안읽음 필터) | `X-User-Id` |
| `PATCH` | `/{id}/read` | 읽음 처리 (멱등, read_at은 첫 시각 고정) | `X-User-Id` |
| `PATCH` | `/read-all` | 안 읽은 알림 일괄 읽음 (`{updatedCount}`) | `X-User-Id` |
| `PATCH` | `/{id}/cancel` | 예약 발송 취소 (PENDING만, 아니면 **409**) | `X-User-Id` |

### 운영자 API — `/api/v1/admin/...` (`X-User-Role: ADMIN`)

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/notifications/{id}/retry` | DEAD_LETTER 단건 수동 재시도 (retry_count 유지) |
| `POST` | `/notifications/retry-batch` | errorCode 일치 DEAD_LETTER 일괄 재시도 (`{retriedCount}`) |
| `GET` | `/notifications/dead-letter` | DEAD_LETTER 목록 |
| `GET` | `/notifications/dead-letter/stats` | errorCode별 집계 (모니터링) |
| `PUT` | `/templates` | 템플릿 업서트 (Mustache 문법 검증, 운영 중 문구 수정) |
| `GET` | `/templates?notificationType=&channel=&language=` | 활성 템플릿 조회 |

오류 응답은 **RFC 7807 ProblemDetail** 형식.

### 요청 예시

```bash
# 발송 요청 (즉시) — eventId로 중복 방지
curl -X POST localhost:8080/api/v1/notifications \
  -H 'Content-Type: application/json' \
  -d '{"recipientId":"user-1","notificationType":"PAYMENT_CONFIRMED",
       "channel":"EMAIL","eventId":"pay-1001","payload":{"amount":5000}}'

# 예약 발송 — scheduledAt 지정 (ISO-8601, UTC)
curl -X POST localhost:8080/api/v1/notifications \
  -H 'Content-Type: application/json' \
  -d '{"recipientId":"user-1","notificationType":"CLASS_REMINDER_D1",
       "channel":"EMAIL","eventId":"class-9","scheduledAt":"2026-06-01T00:00:00Z"}'

# 상태 조회 / 목록 / 읽음
curl localhost:8080/api/v1/notifications/1 -H 'X-User-Id: user-1'
curl 'localhost:8080/api/v1/notifications?unreadOnly=true' -H 'X-User-Id: user-1'
curl -X PATCH localhost:8080/api/v1/notifications/1/read -H 'X-User-Id: user-1'

# 운영자: DEAD_LETTER 모니터링 + 수동 재시도
curl localhost:8080/api/v1/admin/notifications/dead-letter -H 'X-User-Role: ADMIN'
curl -X POST localhost:8080/api/v1/admin/notifications/1/retry -H 'X-User-Role: ADMIN'
```

> **발송은 Mock**이다. 실제 메일은 보내지 않고 로그로 대체한다. 재시도/DLQ 검증을 위해
> `recipientId`가 `fail-*`이면 일시 실패, `permanent-fail-*`이면 영구 실패(즉시 DEAD_LETTER)로 동작한다.

---

## 주요 설정 (`application.yml`)

운영 중 코드 수정 없이 조정 가능. 근거는 [docs/00-DECISIONS.md](docs/00-DECISIONS.md) §3 참고.

| 키 | 기본값 | 의미 |
|---|---|---|
| `notification.scheduling-enabled` | `true` | 이 인스턴스가 Worker 폴링 + Sweeper 실행 여부 |
| `notification.polling.interval` / `batch-size` | `5s` / `50` | 폴링 주기 / 1회 픽업 건수 |
| `notification.retry.max-attempts` | `5` | 초과 시 DEAD_LETTER |
| `notification.retry.initial-backoff` / `multiplier` / `max-backoff` | `1m` / `2` / `16m` | 지수 백오프 (1→2→4→8→16분) |
| `notification.retry.jitter-max` | `30s` | thundering herd 방지(재시도 시각 분산) |
| `notification.sender.timeout` | `2m` | 외부 발송 타임아웃 |
| `notification.sweeper.interval` / `lease-timeout` | `1m` / `10m` | 좀비 감지 주기 / Lease (불변식: ≥⌈batch/concurrency⌉×sender-timeout+마진) |
| `notification.worker.concurrency` | `16` | 인스턴스당 발송 동시성(고정 풀) |
| `spring.datasource.hikari.maximum-pool-size` | `20` | 인스턴스수×풀 ≤ PG `max_connections` |

---

## 스키마 (Flyway: `V1__init.sql`, `V2__seed_templates.sql`)

- **`notification`** — Outbox 테이블. 중복 방지는 `UNIQUE (event_id, recipient_id, channel, notification_type)`
  (event_id NULL이면 dedup 제외 = 관리자 단발 발송 허용). 워커 폴링용 partial index + 사용자 목록 조회용 인덱스.
  Sweeper(1/min)와 DLQ 관리자 조회는 저빈도라 인덱스 없이 풀스캔 허용 (활성 행 작음).
- **`notification_template`** — 타입·채널·언어별 템플릿 (선택 구현). 발송 시 Mustache 렌더링.

---

## 문서

| 문서 | 내용 |
|---|---|
| [docs/00-DECISIONS.md](docs/00-DECISIONS.md) | 선결정 사항 (ADR) — 모든 설계 결정과 근거 |
| [docs/01-ASYNC-RETRY.md](docs/01-ASYNC-RETRY.md) | **비동기 처리 구조 및 재시도 정책 설명** (제출물) |
| [docs/02-REQUIREMENTS-INTERPRETATION.md](docs/02-REQUIREMENTS-INTERPRETATION.md) | **요구사항 해석 및 개선 의견** (제출물) |
| [docs/03-SCALE-ESTIMATION.md](docs/03-SCALE-ESTIMATION.md) | 규모 추정 및 설계 적합성 검증 |

## 기술 스택

- Java 21, Spring Boot 3.5.x, Spring Data JPA(Hibernate)
- PostgreSQL 16, Flyway, JMustache(템플릿 렌더링)
- Gradle(Kotlin DSL) / 테스트: JUnit 5, Testcontainers, AssertJ, Mockito, JaCoCo
