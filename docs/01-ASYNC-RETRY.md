# 01. 비동기 처리 구조 및 재시도 정책

> 과제 추가 제출물 ①. 알림이 어떻게 비동기로 발송되고, 실패 시 어떻게 재시도·복구되는지 설명한다.

---

## 1. 한눈에 보는 구조

```
 ┌──────────┐   1) INSERT(PENDING)    ┌─────────────────────┐
 │ Business │ ──────────────────────▶ │      PostgreSQL      │
 │  Service │   (같은 트랜잭션)        │   notification 테이블 │ ◀── Outbox(큐 역할)
 └──────────┘                         └─────────────────────┘
                                          ▲            ▲
                          2) SKIP LOCKED  │            │ 4) 상태 UPDATE
                             polling      │            │   (SENT/FAILED/...)
                                          │            │
   ┌────────────────┐   @Scheduled   ┌────┴────────────┴───┐
   │  Sweeper        │ ◀───────────── │  NotificationWorker  │
   │ (좀비 복구)      │                │  (Polling Dispatcher)│
   └────────────────┘                └──────────┬───────────┘
                                                 │ 3) Virtual Thread + Semaphore
                                                 ▼
                                        ┌──────────────────┐
                                        │ NotificationSender│ (EMAIL / IN_APP, Mock)
                                        └──────────────────┘
```

핵심 아이디어는 **"DB를 메시지 큐로 사용하는 Transactional Outbox + Polling Worker"** 다.
메시지 브로커 없이도 (1) 트랜잭션 일관성, (2) 재시작 유실 방지, (3) 다중 인스턴스 안전성을
모두 확보하며, 향후 Kafka/SQS로 자연스럽게 전환할 수 있다.

---

## 2. 왜 Outbox + Polling 인가

### 다른 선택지와의 비교

| 방식 | 재시작 유실 | 다중 인스턴스 | 운영 전환 |
|---|---|---|---|
| `@Async` + in-memory | ❌ 유실 | ❌ | ❌ |
| `BlockingQueue` | ❌ 유실 | ❌ | ❌ |
| `@TransactionalEventListener(AFTER_COMMIT)` | ❌ 유실 | ⚠️ | ❌ |
| **DB Outbox + Polling** | ✅ | ✅ | ⭐ Kafka 전환 자연스러움 |

인메모리 큐는 서버가 죽으면 큐 안의 알림이 사라진다. 과제 요구사항
"서버 재시작 후에도 미처리 알림이 유실 없이 재처리"를 만족하려면 **영속 저장소**가 필수이고,
"메시지 브로커 설치 불필요" 제약 하에서 가장 단순한 영속 큐는 **DB 테이블**이다.

### Trade-off (정직하게)

- **Polling 비용**: 빈 polling도 DB 쿼리를 발생시킨다 → 주기 5초로 타협 (부하 vs 지연).
- **지연**: 등록 후 최대 polling 주기(5초)만큼 발송이 늦어질 수 있다. 분 단위 SLA에는 충분.
- **처리량 한계**: DB가 병목. 하루 수만~수십만 건 규모까지는 충분, 그 이상은 Kafka 전환.

---

## 3. 비동기 흐름 상세

### 3-1. 등록 (API 스레드)

```
POST /api/v1/notifications
  → 비즈니스 트랜잭션 안에서 notification 행을 PENDING으로 INSERT
  → 즉시 202 Accepted 응답 (발송은 아직 안 함)
```

API 스레드는 **DB에 쓰기만** 하고 끝난다. 실제 외부 발송(SMTP)은 트랜잭션 밖,
별도 Worker 스레드에서 일어난다. → "API 요청 스레드와 분리" 요구사항 충족.

### 3-2. 발송 (Worker 스레드, `@Scheduled`)

```
매 5초:
  1. SELECT ... WHERE status='PENDING' (or FAILED) AND 발송 시각 도래
       ORDER BY created_at LIMIT 50
       FOR UPDATE SKIP LOCKED            -- 다중 인스턴스 안전
  2. 픽업한 행을 PROCESSING으로 전이 + lease_expires_at = now + 5분
  3. (트랜잭션 커밋 → 락 해제, 하지만 상태가 PROCESSING이라 재픽업 안 됨)
  4. 각 알림을 Virtual Thread로 병렬 발송 (Semaphore로 동시 수 제한)
       성공 → SENT
       일시 실패 → FAILED + retry_count++ + next_retry_at 계산
       영구 실패 → DEAD_LETTER
```

### 3-3. Worker 발송 모델 — Virtual Thread + Semaphore

배치로 픽업한 알림을 **하나씩 순차** 발송하면 외부 응답 지연이 누적된다.
Java 21 Virtual Thread로 각 알림을 병렬 발송하되, 외부 시스템(SMTP) 보호를 위해
`Semaphore`(기본 16, DB 커넥션 풀 크기와 함께 튜닝)로 동시 호출 수를 제한한다.

- 각 알림 발송은 **독립 트랜잭션**이다 → 한 건 실패가 다른 건에 영향 없음 (실패 격리).
- `SKIP LOCKED`가 인스턴스 **간** 분배를 보장하고, Virtual Thread가 인스턴스 **내** 병렬을 담당한다.

---

## 4. "비즈니스에 영향 X" — 어떻게 무시 없이 분리하는가

요구사항: *"알림 처리 실패가 비즈니스 트랜잭션에 영향을 주면 안 되지만, 예외를 단순 무시해서도 안 된다."*

**Transactional Outbox**로 해결한다.

```java
@Transactional
public void onPaymentConfirmed(...) {
    paymentRepository.save(...);              // 비즈니스
    notificationRepository.save(PENDING);     // 알림 등록 (같은 DB 쓰기)
}
// 외부 발송(SMTP)은 트랜잭션 밖 Worker에서 → 발송 실패가 결제에 영향 X
```

### 정직한 trade-off

알림 INSERT는 비즈니스와 **같은 트랜잭션**이므로, 알림 INSERT가 실패하면 비즈니스도 롤백된다.
완벽한 0% 분리는 아니다. 그러나:

- 알림 INSERT 실패 = DB 장애 → 그 상황에선 비즈니스 INSERT도 어차피 실패한다 (**운명 공동체**).
- Outbox의 본질은 **외부 호출(SMTP)을 트랜잭션 밖으로 빼는 것**이지 모든 의존성 제거가 아니다.
- 발송 실패는 "무시"되지 않는다 → `FAILED`/`DEAD_LETTER` 상태와 `last_error_*` 컬럼,
  application log에 모두 기록된다.

진짜 0% 분리(`REQUIRES_NEW` + 별도 저장소)는 같은 DB 환경에서는 과대 설계로 판단했다.

---

## 5. 재시도 정책

### 5-1. Exponential Backoff + Jitter

```
시도 1 실패 → 1분 후
시도 2 실패 → 2분 후
시도 3 실패 → 4분 후
시도 4 실패 → 8분 후
시도 5 실패 → 16분 후
(각 단계 0~30초 랜덤 jitter 추가)
5회 초과 → DEAD_LETTER
```

- **지수 증가**: 일시 장애(보통 수~십 분 내 복구)와 장기 장애 모두 대응.
- **Jitter**: 다중 Worker가 같은 시각에 몰려 외부 서버를 때리는 thundering herd 방지.
- 모든 수치는 `application.yml`에서 조정 가능 (운영 데이터로 튜닝).

`next_retry_at` 컬럼에 다음 시도 시각을 저장하고, Worker polling이
`next_retry_at <= now()` 조건으로 자연스럽게 재픽업한다.

### 5-2. 모든 실패가 재시도 대상은 아니다

| 실패 유형 | 처리 | 이유 |
|---|---|---|
| `SMTPException`, `IOException`, timeout | 재시도 | 일시 장애 가능성 |
| 잘못된 이메일 주소 (`permanent-fail-*`) | **즉시 DEAD_LETTER** | 재시도해도 실패 |
| Rate limit | 재시도 (더 긴 backoff) | 외부 API 보호 |
| 알 수 없는 예외 | 재시도 (보수적) | |

`ErrorClassifier`가 예외를 retryable / permanent로 분류한다.

### 5-3. 실패 사유 기록

`notification` 테이블에 **마지막 시도 정보**를 컬럼으로 저장한다.

```
retry_count        : 3
last_error_code    : SMTP_TIMEOUT
last_error_message : Connection timeout after 30s
last_failed_at     : 2026-05-28T10:01:30Z
```

전체 시도 이력은 application log로 남긴다 (누적 분석은 로그/메트릭 시스템에 위임).
별도 `failure_history` 테이블은 YAGNI로 판단 — 필요 시 컬럼은 그대로 두고 테이블만 추가하면 된다.

### 5-4. 최종 실패(DEAD_LETTER)와 수동 재시도

- `DEAD_LETTER`는 자동 picking 대상에서 제외되고, 운영자만 보는 영역이다.
- 수동 재시도 시 **`retry_count`를 유지**한다.
  - 초기화하면 무한 루프 위험 (재시도 → 실패 → DEAD_LETTER → 재시도 → ...).
  - 유지하면 재시도가 또 실패할 때 즉시 DEAD_LETTER로 돌아가 **자동 차단**된다.
- 단건 / 배치(에러 코드 기준) 수동 재시도 API를 제공한다.

---

## 6. 장애 / 복구 시나리오

### 6-1. 처리 중(PROCESSING) 좀비 복구 — Lease + Sweeper

Worker가 알림을 PROCESSING으로 잡은 직후 죽으면(OOM, kill -9), 그 알림은
PROCESSING에 영원히 갇힌다(좀비). 이를 막기 위해 **Lease(임대)** 개념을 둔다.

```
픽업 시:   status=PROCESSING, lease_expires_at = now + 5분
Sweeper(1분마다):
   UPDATE ... SET status='PENDING'
   WHERE status='PROCESSING' AND lease_expires_at < now()
```

- Lease = Sender 타임아웃(2분) × 2 + 마진 ≈ 5분 → 정상 처리를 좀비로 오인하지 않음.
- 만료된 좀비는 PENDING으로 복구되어 다음 polling에서 다른 Worker가 재처리.

> Edge case: lease 만료 직후 원래 Worker의 발송이 뒤늦게 성공하면 중복 발송 가능.
> 운영 환경에서는 Sender 레벨 idempotency(동일 message-id)로 보완해야 한다 (과제 범위 밖, 문서화).

### 6-2. 서버 재시작 후 유실 없음

```
재시작 전: PENDING 30, PROCESSING 5  (DB에 영속)
재시작 후: 데이터 그대로
   → Sweeper가 PROCESSING 5건을 PENDING 복구
   → Worker가 PENDING 35건 재처리
```

**별도 복구 코드 없이 상태 정의만으로 자동 복구**된다. 정상 종료 시에는 graceful shutdown으로
진행 중 발송을 마치도록 한다.

### 6-3. 다중 인스턴스 중복 처리 없음

`SELECT ... FOR UPDATE SKIP LOCKED`로 여러 Worker가 서로 다른 행을 픽업한다.
락이 걸린 행은 건너뛰므로(SKIP) Worker 간 대기가 없고, 같은 알림이 두 번 처리되지 않는다.
이는 단일 인스턴스 테스트로는 못 잡으므로 **다중 Worker 동시성 통합 테스트**로 증명한다.

---

## 7. 운영 환경 전환 경로 (브로커 도입 시)

```
[현재]  Service → DB Outbox ← Polling Worker → Sender
[전환]  Service → DB Outbox →(CDC/Debezium)→ Kafka → Consumer → Sender
```

`NotificationDispatcher` 인터페이스로 추상화되어 있어, **Worker(폴링)를 Consumer로 교체**하면 된다.

```java
public interface NotificationDispatcher {
    void dispatch(Notification n);
}
// 현재: DbPollingDispatcher   향후: KafkaConsumerDispatcher
```

DB Outbox 테이블 구조는 그대로 유지되므로, CDC(Debezium)로 Outbox를 Kafka에 흘려보내는
점진적 진화가 가능하다.

---

## 8. 설정값 요약 (application.yml)

```yaml
notification:
  polling:   { interval: 5s, batch-size: 50 }
  retry:     { max-attempts: 5, initial-backoff: 1m, multiplier: 2, max-backoff: 16m, jitter-max: 30s }
  sender:    { timeout: 2m }
  sweeper:   { interval: 1m, lease-timeout: 5m }
  worker:    { semaphore-permits: 16, scheduling-enabled: true }
  retention: { enabled: false, sent-days: 30, dead-letter-days: 90 }
```

> "초기값은 가설일 뿐, 운영 데이터로 재조정한다"는 전제로 모든 값을 설정으로 분리했다.
> 상태 머신·중복 방지 등 더 깊은 설계 근거는 [00-DECISIONS.md](00-DECISIONS.md) 참고.
