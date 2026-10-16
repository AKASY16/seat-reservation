# 공연 좌석 예약 시스템

티켓 오픈 직후처럼 같은 공연에 요청이 몰려도 좌석이 두 번 팔리지 않도록 하는 좌석 선점·결제 이벤트 처리 서버입니다.

- Java 21, Spring Boot 4.0, Spring Data JPA
- MySQL 8.4 (InnoDB), Flyway
- 테스트: JUnit 5, Testcontainers(MySQL)
- 부하 시험: k6

설계 결정은 [DECISIONS.md](DECISIONS.md), 하지 못한 것과 감수한 타협은 [NOT-DONE.md](NOT-DONE.md), AI 도구 사용 내역은 [AI-USAGE.md](AI-USAGE.md)에 있습니다.

## 구현 범위

| 지켜야 할 조건 | 상태 | 검증 |
|---|---|---|
| 1. 같은 좌석 이중 판매 금지 | 구현 | `SeatHoldConcurrencyTest` |
| 2. 여러 좌석 선점은 전부 성공 또는 전부 실패 | 구현 | `SeatHoldConcurrencyTest` |
| 3. 같은 요청·결제 이벤트는 한 번만 반영 | 구현 | `SeatHoldConcurrencyTest`, `PaymentEventServiceTest` |
| 4. 선점 만료와 결제 승인이 동시에 일어날 때 DECISIONS.md 11번 규칙대로 처리 | 구현 | `PaymentEventServiceTest` |
| 5. 재시작 후에도 판매 확정 상태 유지 | 구현 | `RestartPersistenceTest` |
| 부하 시험 | 수행 | 아래 [부하 시험](#부하-시험) |

## 실행 방법

필요한 것: JDK 21, Docker

```bash
./gradlew bootJar
docker compose up --build
```

- 애플리케이션: http://localhost:8080
- MySQL: `localhost:3306` (DB `seat_reservation`, 계정 `app` / `app`)

서버 두 대가 같은 DB를 쓰는 구성으로 띄우려면 `multi` 프로필을 켭니다. 두 번째 서버는 8081 포트입니다.

```bash
docker compose --profile multi up --build
```

선점 유지 시간은 `HOLD_TTL` 환경 변수(ISO-8601 기간)로 바꿀 수 있습니다. 기본값은 5분입니다.

```bash
HOLD_TTL=PT30S docker compose up --build
```

DB까지 처음 상태로 되돌리려면 `docker compose down -v`로 볼륨을 함께 지웁니다.

PC에 MySQL이 이미 설치되어 3306 포트를 쓰고 있으면 `db` 컨테이너가 뜨지 않습니다. 로컬 MySQL을 멈추거나 `docker-compose.yml`의 `db` 포트 설정을 바꿔야 합니다.

### 초기 데이터

Flyway 마이그레이션 [`V2__insert_sample_performance.sql`](src/main/resources/db/migration/V2__insert_sample_performance.sql)이 앱이 처음 뜰 때 샘플 데이터를 넣습니다.

- 공연 1개 (id `1`)
- 좌석 1,000석: A~T열, 열마다 50석. 좌석 id는 A-1부터 순서대로 1~1000

좌석을 만드는 API는 두지 않았습니다. 다른 공연이 필요하면 `performance`, `seat` 테이블에 직접 INSERT합니다.

## 테스트

```bash
./gradlew test
```

Docker가 실행 중이어야 합니다. 테스트가 Testcontainers로 MySQL 8.4 컨테이너를 띄워서 실제 MySQL 위에서 돕니다.
동시성 규칙이 InnoDB의 행 잠금 동작에 기대고 있어서 H2 같은 인메모리 DB로는 검증하지 않았습니다.

| 테스트 | 검증 내용 |
|---|---|
| `SeatHoldConcurrencyTest` | 같은 좌석에 50개 요청이 동시에 와도 1개만 성공 / 여러 좌석 중 하나라도 이미 잡혀 있으면 나머지도 잡히지 않음 / 겹치는 다중 좌석 요청 40개를 동시에 보내도 성공한 선점은 좌석을 전부, 실패한 선점은 하나도 갖지 않음 / 같은 Idempotency-Key 동시 요청은 선점 1개 / 만료 작업이 동시에 여러 번 돌아도 1번만 처리 / 만료된 좌석은 만료 작업 전에도 다시 선점 가능 / 만료 처리와 만료된 좌석을 가져가는 새 선점이 동시에 돌아도 데드락 없음 |
| `PaymentEventServiceTest` | 승인 시 판매 확정 / 같은 이벤트 10번 동시 재전송 시 1번만 반영 / 취소가 승인보다 먼저 와도 최종 상태는 취소 / 승인과 취소가 동시에 와도 최종 상태는 취소 / 판매 후 취소되면 좌석 재판매 가능 / 만료 전 승인은 만료 처리와 동시에 와도 판매 확정 / 만료 후 승인은 환불 대상 / 그사이 다른 사람이 좌석을 잡았으면 환불 대상 / 취소된 선점이나 이미 확정된 선점에 온 승인은 환불 대상 |
| `RestartPersistenceTest` | 판매 확정 후 애플리케이션 컨텍스트를 종료하고 새로 띄워도 좌석이 SOLD |
| `ApiTest` | HTTP 요청·응답 형식과 상태 코드 |

시간이 걸리는 만료 테스트는 실제로 기다리지 않고, 테스트용 `Clock`의 시각을 앞으로 옮겨서 확인합니다.

## API

사용자 식별 헤더(`X-User-Id`), 선점 요청의 `Idempotency-Key` 헤더, 응답 형식은 직접 정했습니다.

### 좌석 조회 `GET /performances/{performanceId}/seats`

```json
{
  "performanceId": 1,
  "seats": [
    { "seatId": 1, "seatNo": "A-1", "status": "AVAILABLE" },
    { "seatId": 2, "seatNo": "A-2", "status": "HELD" },
    { "seatId": 3, "seatNo": "A-3", "status": "SOLD" }
  ]
}
```

만료 시각이 지난 선점 좌석은 만료 작업이 아직 정리하지 않았더라도 `AVAILABLE`로 보여 줍니다. 실제로 그 좌석은 바로 다시 선점할 수 있습니다.

### 좌석 선점 `POST /holds`

| 헤더 | 필수 | 설명 |
|---|---|---|
| `X-User-Id` | O | 사용자 식별자. 인증은 범위 밖이라 그대로 신뢰합니다. |
| `Idempotency-Key` | X | 같은 사용자가 같은 키로 다시 보내면 선점을 새로 만들지 않고 처음 만든 선점을 돌려줍니다. |

```json
{ "performanceId": 1, "seatIds": [1, 2] }
```

| 응답 | 경우 |
|---|---|
| `201 Created` | 선점 성공 |
| `200 OK` | 같은 `Idempotency-Key`로 이미 만들어진 선점을 돌려줌 |
| `409 SEAT_UNAVAILABLE` | 요청 좌석 중 하나라도 이미 선점·판매됨. 어느 좌석도 선점되지 않음 |
| `422 IDEMPOTENCY_KEY_REUSED` | 같은 키로 다른 좌석을 요청함 |
| `400` | 헤더 누락, 빈 좌석 목록, 중복 좌석, 한 번에 10석 초과 |

```json
{
  "holdId": "0b6a6c1e-3f0e-4d4e-9b8f-6c5d6f0e2a11",
  "performanceId": 1,
  "seatIds": [1, 2],
  "status": "ACTIVE",
  "expiresAt": "2026-10-18T03:05:00Z"
}
```

`holdId`는 결제를 시작할 때 결제 제공자에게 주문 번호로 넘기는 값입니다. 결제 이벤트가 이 값으로 선점을 찾습니다.

### 선점 취소 `DELETE /holds/{holdId}`

헤더 `X-User-Id`가 필요합니다.

| 응답 | 경우 |
|---|---|
| `204 No Content` | 취소됨. 이미 취소한 선점을 다시 취소해도 204 |
| `404 HOLD_NOT_FOUND` | 선점이 없거나 다른 사용자의 선점 |
| `409 HOLD_EXPIRED` | 이미 만료됨 |
| `409 HOLD_CONFIRMED` | 결제가 끝나 판매 확정됨 |

### 결제 이벤트 수신 `POST /payment-events`

```json
{
  "eventId": "evt-123",
  "paymentId": "pay-789",
  "holdId": "0b6a6c1e-3f0e-4d4e-9b8f-6c5d6f0e2a11",
  "status": "approved",
  "occurredAt": "2026-10-18T03:01:00Z"
}
```

`status`는 `approved`, `cancelled`입니다. 응답은 `200 OK`와 처리 결과입니다.

```json
{ "eventId": "evt-123", "result": "SOLD" }
```

| result | 의미 |
|---|---|
| `SOLD` | 승인 반영, 좌석 판매 확정 |
| `REFUND_REQUIRED` | 승인됐지만 좌석을 줄 수 없어 환불 대상으로 기록 |
| `SALE_CANCELLED` | 판매 확정된 결제가 취소되어 좌석을 다시 판매 가능으로 되돌림 |
| `PAYMENT_CANCELLED` | 좌석에는 영향 없이 결제만 취소로 기록 |
| `IGNORED` | 이미 최종 상태라 반영할 것이 없음 |
| `DUPLICATE` | 이미 처리한 `eventId` |

중복이거나 무시한 이벤트도 200으로 답합니다. 실패 응답을 받은 제공자는 같은 이벤트를 계속 다시 보내기 때문입니다.
선점을 찾을 수 없거나 이미 다른 선점에 연결된 `paymentId`면 `422`, 처리 중 오류가 나면 `5xx`가 나가고, 제공자의 재전송으로 다시 처리됩니다.
상태 전이 규칙은 [DECISIONS.md](DECISIONS.md#결제-이벤트)에 정리했습니다.

## 부하 시험

### 조건

아래 조건으로 돌렸습니다. 스크립트는 [`load-test/hold-load.js`](load-test/hold-load.js)입니다.

- 공연 1개, 좌석 1,000석 (샘플 데이터)
- 동시 요청 200 (k6 VU 200), **총 5,000건**
- 요청마다 좌석 1~4개를 연속으로 선택
- 요청의 60%는 앞 두 열(A, B열, 좌석 100석)을 노림

### 실행 방법

```bash
./gradlew bootJar
docker compose up -d --build

# 선점·결제 기록을 지우고 좌석을 모두 빈 좌석으로 되돌린다
docker compose exec -T db mysql -uroot -proot seat_reservation < load-test/reset.sql

docker run --rm --network host -v "$PWD/load-test:/scripts" grafana/k6:2.3.0 run /scripts/hold-load.js

# 정합성 확인. 앞의 세 값이 모두 0이어야 한다
docker compose exec -T db mysql -uroot -proot seat_reservation < load-test/verify.sql
```

Windows나 macOS의 Docker Desktop에서는 `--network host`가 호스트에 연결되지 않으므로, 대신 `-e BASE_URLS=http://host.docker.internal:8080`을 넘깁니다.

서버 두 대로 돌리려면 `docker compose --profile multi up -d --build` 후 k6에 `-e BASE_URLS=http://localhost:8080,http://localhost:8081`을 넘깁니다. 요청을 두 서버에 번갈아 보냅니다.

### 결과: 이중 판매 금지 같은 조건이 부하 중에도 지켜졌는가

제 PC에서 최종 코드로 돌린 결과입니다.

- PC: AMD Ryzen 7 5700X3D (8코어 16스레드), 메모리 32GB, Windows 11, Docker Desktop (VM에 16 vCPU)
- 애플리케이션, MySQL, k6(`grafana/k6:2.3.0`)를 모두 같은 PC의 Docker에서 실행
- 예열 실행을 몇 번 한 뒤 3회 측정 (서버 1대)

| | 처리량 | 평균 | 중앙값 | p95 | 최대 | 201 | 409 | 그 밖 |
|---|---|---|---|---|---|---|---|---|
| 1회 | 2,751 req/s | 69.7ms | 64.1ms | 151.1ms | 311.7ms | 457 | 4,543 | 0 |
| 2회 | 2,695 req/s | 70.1ms | 64.1ms | 157.7ms | 348.3ms | 433 | 4,567 | 0 |
| 3회 | 2,858 req/s | 66.8ms | 60.4ms | 148.2ms | 284.3ms | 431 | 4,569 | 0 |

세 번 모두 [`verify.sql`](load-test/verify.sql)의 세 검사가 0이었습니다.

- 만료되지 않은 선점이 둘 이상 걸린 좌석: 0
- 유효한 선점인데 자기 좌석 중 일부를 갖지 못한 선점(전부 또는 전무 위반): 0
- 유효한 선점에 속하지 않은 HELD 좌석: 0

선점된 좌석은 841~860석이었고 인기 구간 100석은 매번 모두 찼습니다. 응답은 201 또는 409뿐이었고, InnoDB 데드락(`INNODB_METRICS`의 `lock_deadlocks`)은 0건이었습니다. 서버 2대로 띄워 돌린 2회도 검사 결과는 모두 0이었습니다. k6의 `http_req_failed`가 91%로 나오는 건 409를 실패로 세기 때문입니다.

### 측정 방법

- 처리량과 응답 시간: k6의 `http_reqs`, `http_req_duration`
- DB: 측정 전에 `performance_schema.events_statements_summary_by_digest`를 비우고, 끝난 뒤 쿼리 종류별 실행 횟수와 누적 시간을 봤습니다. 행 잠금은 `Innodb_row_lock%` 상태값의 시작과 끝 차이로 셌습니다.
- CPU: 컨테이너별 cgroup CPU 사용 시간을 실행 시간으로 나눈 평균과 `docker stats`의 1초 샘플

측정의 한계

- k6, 애플리케이션, MySQL이 같은 PC에서 돌기 때문에 실제 서버 환경의 수치로 볼 수 없습니다. 특히 커밋할 때의 디스크 동기화 시간은 이 PC 디스크의 값입니다.
- 한 번 실행이 2초 안팎이라 CPU는 1초 샘플이 2~3개뿐입니다. 평균값을 위주로 봤습니다.
- 좌석 1,000석이 금방 차기 때문에 요청의 90% 이상이 409로 끝납니다. 이 시험은 "충돌이 많은 상황에서 빨리 거절하는가"를 주로 잽니다.
- k6는 응답을 받아야 다음 요청을 보냅니다(closed model). 서버가 느려지면 보내는 속도도 같이 줄어서, 실제 트래픽이 몰릴 때보다 지연이 낮게 잡힐 수 있습니다.
- 커넥션 풀 대기 시간(Hikari pending)은 직접 재지 않았습니다. 아래 풀 관련 내용은 DB 쪽 수치로 추정한 것입니다.

### 가장 먼저 보인 병목: DB의 커밋과 롤백

CPU는 여유가 있었습니다. 서버 1대일 때 애플리케이션이 약 3.9코어, MySQL이 약 2.9코어, k6가 약 1.7코어를 써서 16코어의 절반 정도였습니다.

DB 안에서 시간을 쓴 곳은 쿼리보다 트랜잭션의 끝이었습니다(3회 평균).

| 문장 | 실행 수 | 누적 시간 | 평균 |
|---|---|---|---|
| `COMMIT` | 약 440 | 약 10.5초 | 약 24ms |
| `ROLLBACK` | 약 4,560 | 약 9.7초 | 약 2.1ms |
| 좌석 선점 `UPDATE` | 약 5,000 | 약 3.5초 | 약 0.7ms |
| `SET autocommit` | 약 10,000 | 약 2.1초 | 약 0.2ms |
| 선점 행 `INSERT` 2종 | 약 1,300 | 약 0.3초 | 약 0.25ms |

- `COMMIT` 한 번이 평균 24ms로 가장 무겁습니다. MySQL 기본 설정(`innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`)이라 커밋마다 redo 로그와 binlog를 디스크에 동기화합니다.
- 실패한 요청 4,500여 건도 `ROLLBACK`으로 끝나서, 건당 비용은 작아도 합치면 커밋과 비슷합니다.
- DB 문장 시간의 합(실행당 약 26초)을 실행 시간(약 1.8초)으로 나누면 DB 안에서 평균 14~15개 연결이 늘 일하고 있었다는 뜻입니다. 커넥션 풀이 20개이므로, 200명의 요청 대부분은 풀에서 연결을 기다렸을 것으로 봅니다.
- 행 잠금 대기는 실행마다 210~260회, 총 1~1.4초(대기당 약 5ms)로 DB 시간의 5% 정도였습니다.

서버를 2대로 늘려 같은 DB를 쓰게 하면 처리량은 2,797 req/s로 1대일 때와 같았습니다. 연결은 40개로 늘었지만 DB 안의 대기가 그만큼 길어졌습니다.

| | 서버 1대 | 서버 2대 |
|---|---|---|
| 처리량 | 2,695~2,858 req/s | 2,797 req/s |
| `COMMIT` 평균 | 약 24ms | 37ms |
| 좌석 `UPDATE` 평균 | 약 0.7ms | 3.0ms |
| 행 잠금 대기 | 약 240회, 1.2초 | 618회, 8.0초 |

### 실제 배포에 가깝게: 서버마다 CPU를 나누고 DB 왕복 지연을 넣었을 때

위 측정은 서버 한 대가 PC의 CPU를 마음껏 쓰고, 서버와 DB 사이 지연도 거의 없는 상태입니다. 실제로는 서버 여러 대가 각자 정해진 CPU로 돌고, DB는 네트워크 너머에 있습니다. 그래서 같은 PC에서 조건을 이렇게 바꿔 다시 쟀습니다.

- 컨테이너 CPU 제한: 애플리케이션 서버마다 3코어, MySQL 4코어, k6 3코어
- DB 왕복 지연: DB 컨테이너에서 나가는 패킷에 `tc netem`으로 1ms를 더함. 서버에서 DB로 ping이 0.07ms에서 1.09ms가 됨. k6와 서버 사이에는 지연을 넣지 않음
- 서버를 늘려도 다른 조건은 같음 (서버마다 커넥션 풀 20)

| | 처리량 (평균) | p95 (평균) | 좌석 `UPDATE` 평균 | `COMMIT` 평균 | 행 잠금 대기 (실행당) |
|---|---|---|---|---|---|
| 서버 1대, 지연 없음 (1회) | 2,141 req/s | 199ms | 0.45ms | 19ms | 247회, 1.0초 |
| 서버 3대, 지연 없음 (3회) | 2,639 req/s | 158ms | 5.3~6.6ms | 35~47ms | 약 1,000회, 18~21초 |
| 서버 1대, 지연 1ms (3회) | 1,555 req/s | 246ms | 0.44ms | 14~16ms | 약 320회, 1.1초 |
| 서버 3대, 지연 1ms (3회) | 2,287 req/s | 203ms | 2.7~3.2ms | 24~28ms | 약 1,040회, 10~12초 |

16회 모두 응답은 201 또는 409뿐이었고, `verify.sql`의 세 검사는 0, 데드락도 0건이었습니다. 서버 3대가 같은 좌석을 동시에 노리고 DB 왕복이 느려져도 이중 판매나 부분 선점은 없었습니다.

- CPU를 3코어로 묶으면 서버 1대는 CPU 한도(300%)까지 차서, 위 측정(약 2,750 req/s)보다 처리량이 낮습니다. 이 조건에서는 서버를 늘리면 처리량이 오릅니다.
- 하지만 서버를 3배로 늘려도 처리량은 1.2~1.5배였습니다. 연결이 20개에서 60개로 늘면서 인기 좌석의 행 잠금 대기가 4배쯤 늘고, 좌석 `UPDATE`가 0.4ms에서 3~6ms로 느려졌습니다. 서버는 늘었지만 같은 좌석 행을 두고 DB 안에서 줄을 서는 시간이 늘어난 것입니다.
- DB 왕복 지연 1ms는 서버 1대일 때 처리량을 27% 떨어뜨렸고, 서버 3대일 때는 13%만 떨어뜨렸습니다. 요청 하나가 DB와 여러 번 왕복하므로, 지연이 있으면 연결 하나가 처리할 수 있는 요청 수가 줄고, 그만큼 서버와 연결을 늘리는 효과가 커집니다.
- 서버 3대에서 지연을 넣었더니 DB 안의 `UPDATE`와 `COMMIT`은 오히려 빨라졌습니다. 요청이 DB에 도착하는 속도가 느려져 동시에 겹치는 트랜잭션이 줄었기 때문으로 보입니다(추정).

정리하면 지금 구조에서 먼저 막히는 곳은 애플리케이션 서버가 아니라 DB가 처리할 수 있는 커밋 수이고, 그 뒤로 인기 좌석의 행 잠금 경합이 따라옵니다. 서버를 늘리면 어느 정도 오르지만, 늘린 만큼 DB 안의 대기가 길어져서 오르는 폭이 금방 줄어듭니다. 더 올리려면 커밋 비용 자체를 줄이거나(디스크 동기화 정책, 더 빠른 디스크), 인기 좌석으로 가는 요청을 DB 앞에서 줄이는 방법(대기열 등)을 봐야 하는데, 이번에는 하지 않았습니다.

### 개발 중에 고친 것: 실패하는 요청의 비용

처음 구현은 선점 행(`seat_hold`, `seat_hold_item`)을 먼저 INSERT하고 좌석을 UPDATE했습니다. 같은 `Idempotency-Key` 요청이 겹칠 때 좌석을 건드리기 전에 유니크 제약으로 걸러 내려는 의도였습니다. 그런데 요청의 90% 이상이 좌석 충돌로 실패하는 상황에서는, 실패할 때마다 선점 행을 INSERT했다가 롤백하는 비용이 DB 시간의 큰 부분을 차지했습니다. 좌석 UPDATE를 먼저 하고 성공했을 때만 INSERT하도록 순서를 바꿨고(DECISIONS.md 7번), 위 표에서 선점 행 INSERT가 성공한 선점 수만큼만 실행되는 것이 그 결과입니다.

## 프로젝트 구조

```
src/main/java/com/seatreservation
├── seat          좌석 조회, 좌석 상태 변경 쿼리
├── hold          선점 생성·취소·만료, 만료 스케줄러
├── payment       결제 이벤트 처리
├── performance   공연
├── config        Clock 설정
└── common        예외 처리
src/main/resources/db/migration   스키마와 샘플 데이터
load-test                          k6 스크립트, 초기화·검증 SQL
```

도메인마다 `controller`, `service`, `repository`, `domain`(엔티티와 상태 enum), `dto`로 나눴습니다. `hold`에는 만료 작업(`scheduler`)과 선점 설정값(`config`)이 더 있습니다.
