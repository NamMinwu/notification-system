# 00. 선결정 사항 (Architecture Decision Record)

> 구현 전에 합의가 필요했던 모든 결정을 한 곳에 모은 문서입니다.
> 각 결정은 "선택지 → 채택 → 근거" 순으로 정리했고, 상세 trade-off는 별도 문서(`01`~`07`)를 참고하세요.

---

## 1. 환경 / 인프라

| 항목 | 채택 | 근거 |
|---|---|---|
| 데이터베이스 | **PostgreSQL 16** | `FOR UPDATE SKIP LOCKED`, `ON CONFLICT`, JSONB, Partial Index 모두 네이티브 지원 |
| 언어/런타임 | **Java 21** | Virtual Thread 활용 (Worker 발송 병렬화) |
| 프레임워크 | **Spring Boot 4.0.x + JPA(Hibernate)** | 과제 지정 스택 (최신 GA) |
| 빌드 도구 | **Gradle (Kotlin DSL)** | 최신 Spring Boot 권장, 타입 안전한 빌드 스크립트 |
| 마이그레이션 | **Flyway** | 버전 관리되는 스키마, 운영 전환 가능 |
| 인증/인가 | **`X-User-Id` 헤더 기반 간소화** | 과제 허용 범위. 권한 검증(본인 알림만 조회/읽음)은 유지 |
| group / artifact | **`com.notification` / `notification-system`** | 짧고 목적 명확 |

---

## 2. 도메인 모델

### 2.1 상태 머신 — **6-state** 채택

```
PENDING ──▶ PROCESSING ──▶ SENT
   │            │   ╲
   │            │    ╲──▶ FAILED ──▶ PROCESSING (재시도)
   │            │            ╲──────▶ DEAD_LETTER
   │            └──▶ PENDING (Lease 만료 좀비 복구)
   │            └──▶ DEAD_LETTER (영구 실패 즉시 종료)
   └──▶ CANCELLED (예약 발송 취소)

DEAD_LETTER ──▶ PENDING (수동 재시도 재큐잉 → 워커가 다시 claim)
```

| 상태 | 의미 | 진입 | 다음 |
|---|---|---|---|
| `PENDING` | 발송 대기 | 등록 / 재시도 시각 도래 / 좀비 복구 | PROCESSING, CANCELLED |
| `PROCESSING` | 발송 시도 중 (Lease 보유) | Worker 락 획득 | SENT, FAILED, DEAD_LETTER, PENDING |
| `SENT` | 발송 성공 | Sender 성공 | (종료) |
| `FAILED` | 일시 실패, 재시도 대기 | retryable 예외 + retry_count < MAX | PROCESSING, DEAD_LETTER |
| `DEAD_LETTER` | 최종 실패 | 영구 예외 / retry_count ≥ MAX | PENDING (수동 재시도 재큐잉) |
| `CANCELLED` | 예약 취소됨 | PENDING에서 취소 요청 | (종료) |

> **요구사항 trade-off 문서는 5-state를 권장**했으나, 선택 구현(발송 스케줄링)의 **예약 취소**를 명시적으로 다루기 위해 `CANCELLED` 종료 상태를 1개 추가했다. 취소는 `PENDING` 상태에서만 가능하며(이미 PROCESSING/SENT면 불가), 감사 이력이 남고 worker polling 대상에서 자연히 제외된다. Soft delete 대비 "사용자가 의도적으로 취소했다"는 의미가 상태로 명확히 표현된다.

상태 전이는 `NotificationStatus.canTransitionTo(next)` 로 도메인 레벨에서 강제한다.

### 2.2 중복 방지 (Idempotency) — **4컬럼 복합 UNIQUE**

```
UNIQUE (event_id, recipient_id, channel, notification_type)
```

- "동일 이벤트" = `event_id + recipient_id + channel + notification_type` 조합.
  - 같은 결제, 같은 사용자, EMAIL 2회 → **중복**
  - 같은 결제, 같은 사용자, EMAIL + IN_APP → 다른 알림 (채널 다름)
  - 같은 결제, 다른 사용자 → 다른 알림
- 동시성은 **DB UNIQUE 제약에 위임** (애플리케이션 `if exists` 체크는 race condition 취약).
- 중복 INSERT 시 `DataIntegrityViolationException` → 기존 레코드 ID 반환 (멱등 응답: 첫 요청 201, 재요청 200).
- **`event_id`가 NULL이면 dedup 안 함** — PostgreSQL은 NULL을 distinct로 취급하므로, 관리자 단발 발송 등 이벤트 없는 알림은 중복 검사 없이 허용된다.

> 요구사항 trade-off 문서는 SHA-256 해시 키를 권장했으나, 동일한 4요소를 **복합 UNIQUE 제약**으로 직접 표현하는 쪽을 택했다. 컬럼이 그대로 노출되어 디버깅·운영 조회가 쉽고, 해시 충돌 고민이 없다.

### 2.3 NotificationType — **4종 enum 고정 (확장 가능)**

```java
ENROLLMENT_COMPLETE     // 수강 신청 완료
PAYMENT_CONFIRMED       // 결제 확정
CLASS_REMINDER_D1       // 강의 시작 D-1
CANCELLATION_PROCESSED  // 취소 처리
```

- 타입에 비즈니스 분기 로직을 두지 않는다 (값 객체). Worker/Sender는 `channel`만 보고 동작.
- 새 타입 추가 = enum 값 + 템플릿 등록만으로 동작.

### 2.4 Payload — **JSONB**

```java
@Column(columnDefinition = "jsonb")
private Map<String, Object> payload;
```

- 명세가 "참조 데이터(이벤트 ID, 강의 ID **등**)"로 확장성을 열어둠 → 타입별 가변 필드를 유연 저장.
- 위험(컴파일 타임 미검증)은 발송 직전 **템플릿 변수 매칭 검증**으로 완화.

### 2.5 Priority — **미도입** (FIFO, `created_at` 정렬)

---

## 3. 정책 / 설정값 (application.yml)

> 모든 값은 설정으로 분리하여 운영 중 코드 수정 없이 조정 가능.

```yaml
notification:
  scheduling-enabled: true  # 이 인스턴스가 백그라운드 스케줄(Worker 폴링 + Sweeper)을 실행
  polling:
    interval: 5s          # SLA(1분)의 1/12 안전 마진
    batch-size: 50        # 1회 polling 픽업 건수 (LIMIT)
  retry:
    max-attempts: 5       # 초과 시 DEAD_LETTER
    initial-backoff: 1m   # 1 → 2 → 4 → 8 → 16분
    multiplier: 2
    max-backoff: 16m
    jitter-max: 30s       # thundering herd 방지
  sender:
    timeout: 2m           # Mock이지만 설계상 외부 호출 타임아웃
  sweeper:
    interval: 1m          # 좀비 감지 주기
    lease-timeout: 5m     # Sender timeout × 2 (좀비 판정 기준)
  worker:
    semaphore-permits: 16 # Virtual Thread 동시 발송 제한 (DB 풀 크기와 함께 튜닝)
  retention:
    enabled: false        # 기본 비활성 (운영 시 활성화)
    sent-days: 30
    dead-letter-days: 90
```

| 정책 | 값 | 근거 |
|---|---|---|
| Polling 주기 | 5초 | SLA 1분 / 12 (보수적) |
| 배치 크기 | 50 | DB 락 점유 vs 처리량 균형 |
| 최대 재시도 | 5회 | 1+2+4+8+16 ≈ 31분, 일시 장애 평균 복구 시간 내 |
| Backoff | exp + jitter | 부하 분산, 일시/장기 장애 모두 대응 |
| Sender 타임아웃 | 2분 | 외부 SMTP 가정값 |
| Lease 타임아웃 | 5분 | Sender 타임아웃 × 2 + 마진 (정상 처리 오인 방지) |
| Sweeper 주기 | 1분 | 좀비 복구 지연 vs DB 부하 |
| **Retention** | **잡 구현 + 기본 비활성** | 설계 역량은 보이되, 과제 데이터 오삭제 방지 (SENT 30일 / DEAD_LETTER 90일) |

**테스트 프로파일(`application-test.yml`)** 에서는 타임아웃/주기를 초 단위로 단축한다 (예: lease 10초). 단, 시간 의존 단위 테스트는 **Clock 주입**으로 결정적으로 검증한다.

### 커넥션 풀 ↔ Semaphore ↔ 다중 인스턴스 사이징

발송 병렬화(Virtual Thread)에서 각 발송은 자기 트랜잭션 = 커넥션 1개를 점유하므로, 세 값이 함께 묶인다.

- **인스턴스 내부**: `worker.semaphore-permits(16) < datasource.hikari.maximum-pool-size(20)` — 세마포어가 외부 호출 동시성을 제한하되, 풀보다 작아야 발송 스레드가 커넥션 대기로 막히지 않는다(claim 트랜잭션·API 트래픽 여유 확보).
- **다중 인스턴스(전역 상한)**: HikariCP 풀은 **인스턴스당**이고, 진짜 상한은 PostgreSQL `max_connections`(기본 100). 따라서
  ```
  인스턴스 수 × 인스턴스당 풀 크기 + 여유 ≤ postgres max_connections
  ```
  풀 20 기준 약 4인스턴스가 안전선. 더 늘리려면 **인스턴스당 풀/세마포어를 줄이거나**(큰 풀은 DB 내부 경합만 키움) **PgBouncer**(커넥션 풀러)를 앞에 둔다. "다중 인스턴스 = 풀을 키운다"가 아니라 그 반대.
- `SELECT ... FOR UPDATE SKIP LOCKED` 덕분에 인스턴스끼리 풀을 공유하지 않고 서로 다른 행을 처리하므로, DB가 보는 총 커넥션만 인스턴스 수에 선형 증가한다. (다중 워커가 각 알림을 정확히 1회 처리함은 `WorkerConcurrencyIT`로 검증.)

---

## 4. 구현 디테일

| 항목 | 채택 | 근거 |
|---|---|---|
| 비즈니스 영향 분리 | **Transactional Outbox** | 비즈니스 + 알림 INSERT 동일 트랜잭션, 외부 발송만 분리 |
| 비동기 구조 | **DB Outbox + Polling Worker** | 브로커 없이 재시작 유실 방지, Kafka 전환 가능 |
| 디스패치 추상화 | **`NotificationDispatcher` 인터페이스** | 향후 Kafka Consumer로 구현체 교체 |
| 채널 추상화 | **`NotificationSender` 인터페이스** (채널별 구현) | PUSH/SMS 확장 시 구현체만 추가 |
| 다중 인스턴스 | **`SELECT FOR UPDATE SKIP LOCKED`** | 워커 간 행 분배, 락 대기 없음 |
| **Worker 발송 모델** | **Virtual Thread + Semaphore 병렬** | 배치 내 알림을 가상 스레드로 병렬 발송, 외부 부하는 Semaphore(기본 16, DB 풀과 함께 튜닝) 제한. 각 알림 = 독립 트랜잭션(실패 격리) |
| 좀비 복구 | **Lease + Sweeper** | `lease_expires_at` 만료 행을 PENDING 복구 |
| 실패 사유 기록 | **컬럼 방식** (`last_error_code/message/at`, `retry_count`) + App Log | 마지막 에러는 DB, 전체 이력은 로그 시스템 위임 |
| 실패 분류 | retryable vs permanent | 영구 실패(잘못된 이메일 등)는 즉시 DEAD_LETTER |
| Mock Sender | **설정 가능 실패율 + 이메일 패턴 강제 실패** | `fail-*@*` 무조건 실패, `permanent-fail-*` 즉시 DEAD_LETTER → 재시도/DLQ 테스트 |
| 템플릿 엔진 | **Mustache (JMustache)** | logic-less, XSS 안전, 단순 치환에 적합. 업서트 시 문법 검증 |
| 템플릿 소유권 | **알림 서비스가 소유** | 표현(문구)은 발송 책임자 관심사. 호출자는 payload(데이터)만 전달 |
| 템플릿 저장/렌더 | **DB 테이블 + 발송 시 렌더링** (`TemplateRenderer` 인터페이스) | 운영 중 문구 수정·다국어. "데이터는 등록 시 고정, 표현은 발송 시 최신 템플릿". 캐시는 다중 인스턴스 무효화 불가로 미도입(항상 DB 조회) |
| 수동 재시도 | **retry_count 유지** | 무한 루프 자동 차단, 누적 이력 보존 |
| 과거 시각 예약 | **허용 (즉시 발송)** | 배치 등록 후 일괄 발송 유연성 |
| 다국어 | **language 컬럼만 추가, 'ko' 고정** | 확장성 확보, 현재 미사용 |

---

## 5. 테스트 전략

| 종류 | 도구 | 범위 |
|---|---|---|
| 단위 | JUnit 5 + AssertJ + Mockito | 상태 머신 전이, RetryPolicy(backoff+jitter), 실패 분류, 멱등 키 |
| 통합 | Spring Boot Test + **Testcontainers(PostgreSQL)** + MockMvc | API, Worker(SENT/FAILED/DEAD_LETTER), Scheduled, Sweeper, Template |
| 동시성 | `CountDownLatch` + `ExecutorService` | 동일 이벤트 100건 → 1건 / 5워커×100건 → 정확히 1회씩 / 읽음 멱등 / 동시 재시도 |
| 커버리지 | JaCoCo | line 80% / branch 70%, `gradle check` 자동 검증 |

- **DB 격리**: 컨테이너 1개 재사용 + 각 테스트 후 TRUNCATE.
- **시간 제어**: `java.time.Clock` Bean 주입 → 테스트는 고정 Clock. 실시간 동작 검증은 test 프로파일 + Awaitility.
- **개발 방식**: 도메인 핵심은 **Strict TDD (RED → GREEN → REFACTOR)**.

---

## 6. 결정 ↔ 요구사항 매핑

| 요구사항 | 핵심 결정 |
|---|---|
| 비즈니스 영향 X (무시 금지) | Transactional Outbox + 실패 사유 기록 |
| 상태 관리 / 실패 사유 | 6-state 머신 + last_error 컬럼 |
| 재시도 | Exponential backoff + jitter, 5회 |
| 중복 발송 방지 (동시 요청 포함) | 4컬럼 복합 UNIQUE + 멱등 응답 |
| 비동기 (브로커 없이, 전환 가능) | DB Outbox + Polling + Dispatcher 추상화 |
| 처리 중 상태 지속 복구 | Lease + Sweeper |
| 재시작 유실 X | DB가 큐 역할 |
| 다중 인스턴스 중복 처리 X | `FOR UPDATE SKIP LOCKED` |
| (선택) 발송 스케줄링 | `scheduled_at` 컬럼 + CANCELLED 상태 |
| (선택) 템플릿 관리 | 알림 서비스 소유 DB 템플릿 + Mustache 발송 시 렌더링 (`TemplateRenderer` 인터페이스) |
| (선택) 읽음 처리 | 멱등 UPDATE (`WHERE is_read = false`) |
| (선택) 수동 재시도 | retry_count 유지 + 단건/배치 API |
