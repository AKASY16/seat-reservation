# 최종 버전 확인 실행 스크립트

기본 부하 조건(200 VU, 총 5,000건)으로 서버 1대 구성(A)과 서버 2대 구성(B)을 차례로 돌리고, 실행마다 원본 자료와 판정을 남깁니다. 조건과 결과 해석은 [README.md의 부하 시험](../../README.md#부하-시험)에 있습니다.

## 실행

저장소 루트에서 실행합니다. Docker와 bash가 필요하고, Windows에서는 Git Bash로 실행합니다.

```bash
bash load-test/final-check/run.sh
```

| 환경 변수 | 기본값 | 뜻 |
|---|---|---|
| `CONFIGS` | `A B` | 돌릴 구성 |
| `WARMUPS` | `3` | 구성마다 예열 횟수 |
| `RUNS` | `3` | 구성마다 정식 측정 횟수 |
| `GAP_SECONDS` | `30` | 정식 측정 전에 앞 실행의 k6가 끝나고 기다리는 시간 |
| `BUILD_MODE` | `docker` | JAR 빌드 방식. `docker`는 `eclipse-temurin:21-jdk` 컨테이너에서, `local`은 PC의 JDK 21로 빌드 |

서버 1대 구성에서 예열과 정식 측정을 각각 한 번씩 실행하려면 `CONFIGS=A WARMUPS=1 RUNS=1 bash load-test/final-check/run.sh`를 사용합니다.

종료 코드는 모든 실행이 성공 판정이면 0, 하나라도 실패하면 1, 빌드나 서버 기동처럼 준비 단계에서 멈추면 2입니다.

## 하는 일

1. 측정 대상 코드(HEAD, 미커밋 변경, 애플리케이션 코드의 HEAD 대비 변경)와 PC, Docker 환경을 `environment.txt`에 남깁니다.
2. JAR를 빌드하고 앱 이미지(`seat-loadcheck-app:<커밋>`, 애플리케이션 코드에 미커밋 변경이 있으면 `-dirty`)를 만든 뒤, 빌드한 JAR와 이미지 안 JAR의 SHA-256이 같은지 확인합니다.
3. 전용 Compose 프로젝트 `seat-loadcheck`([compose.yml](compose.yml))의 컨테이너와 볼륨만 지우고 MySQL을 새로 띄웁니다. 개발용 `docker-compose.yml`의 DB와 볼륨은 건드리지 않습니다. 직접 확인할 때 쓸 수 있게 MySQL은 `127.0.0.1:13306`, 서버는 `127.0.0.1:18080`, `127.0.0.1:18081`에 열어 둡니다.
4. 구성 A는 app-1만 띄우고, app-2가 멈춰 있어 k6 네트워크에서 응답하지 않는지 확인합니다. 구성 B는 app-1, app-2를 새로 띄웁니다. 떠 있는 서버와 설정은 구성 폴더의 `servers.txt`에 남깁니다.
5. 실행마다 다음 순서로 진행합니다.
   - [`reset.sql`](../reset.sql)로 선점·결제·이벤트 기록을 지우고, 좌석 1,000석이 모두 `AVAILABLE`인지 확인합니다.
   - 정식 측정이면 앞 실행의 k6가 끝나고 `GAP_SECONDS`가 지날 때까지 기다립니다.
   - 행 잠금·데드락 카운터를 읽고 `performance_schema`의 문장 통계를 비웁니다.
   - k6를 같은 Docker 네트워크(`seat-loadcheck-net`)에서 띄워 `http://app-1:8080`, `http://app-2:8080`으로 요청합니다.
   - 문장 통계, 카운터, CPU 사용 시간, 실행 중 서버 로그, [`verify.sql`](../verify.sql) 결과를 저장하고 판정합니다.
6. 끝나면 전용 컨테이너를 멈추고(볼륨은 남김) `summary.md`를 만듭니다.

## 판정

아래를 모두 만족해야 성공입니다. k6 종료 코드만으로 판정하지 않습니다.

- 실행 전 확인 통과 (초기화 성공, 좌석 1,000석이 모두 `AVAILABLE`)
- 요청 5,000건, 완료한 반복 5,000회, 중단된 반복 0회
- 201 + 409 = 5,000, 예상 밖 HTTP 응답 0, 전송 오류 0
- check 성공 5,000건, 실패 0건
- `verify.sql`의 세 검사가 모두 0
- 측정 중 데드락 증가 0
- k6 threshold 통과

409는 이 시나리오에서 정상적인 충돌 응답입니다. k6 기본 지표 `http_req_failed`는 409도 실패로 세므로 판정에 쓰지 않고 따로 기록합니다.

## 결과 파일

결과는 `load-test/results/final-check-<시작 시각 UTC>/`에 남습니다. 얻지 못한 값은 0이 아니라 `미측정`으로 적습니다.

| 파일 | 내용 |
|---|---|
| `summary.md` | 실행별 결과 표 |
| `runs.tsv` | 실행마다 한 행. `summary.md`의 원본 값 |
| `run.log` | 진행 기록과 실행별 판정 |
| `environment.txt` | 코드 버전, JAR·이미지 해시, PC·Docker·MySQL 설정 |
| `build.log`, `image-build.log`, `compose.log` | 빌드와 Compose 출력 |
| `<구성>/servers.txt` | 떠 있는 서버, 이미지, 환경 변수, 서비스 이름으로 접근 확인 |

실행 폴더(`<구성>/warmup-N`, `<구성>/run-N`)

| 파일 | 내용 |
|---|---|
| `command.txt` | 실제로 실행한 k6 명령 |
| `k6.log`, `k6-summary.json` | k6 출력과 요약 원본 |
| `k6-metrics.env` | 실행 스크립트가 읽는 값 (`hold-load.js`의 `handleSummary`가 씀) |
| `reset.txt`, `ready-check.txt` | 초기화 출력과 실행 전 좌석·기록 수 |
| `db-counters-before.tsv`, `db-counters-after.tsv` | 행 잠금 대기와 데드락 카운터 (측정 직전과 직후) |
| `statements-by-kind.tsv` | 서버(접속 주소)별, 문장 종류별 실행 수와 시간. `app` 계정만 |
| `statements-by-digest.tsv` | 쿼리 형태별 실행 수와 시간. 계정을 가리지 않아 관측 쿼리도 들어 있음 |
| `cpu.txt`, `k6-cpu-before.txt`, `k6-cpu-after.txt` | 컨테이너 cgroup의 CPU 사용 시간과 평균 코어 수 |
| `app-1.log`, `app-2.log` | 실행 중 서버 로그 |
| `verify.txt` | `verify.sql` 원본 출력 |
| `connections.tsv` | 실행 직후 서버별 DB 연결 수 |
| `innodb-status.txt` | `SHOW ENGINE INNODB STATUS` |
| `verdict.txt` | 판정, 실패 이유, k6 시작·끝 시각과 앞 실행부터의 간격 |

## 정리

전용 구성의 컨테이너와 볼륨, 빌드 캐시, 이미지를 지우려면 다음을 실행합니다. 이미지 태그는 실행한 커밋입니다.

```bash
docker compose -p seat-loadcheck -f load-test/final-check/compose.yml down -v
docker volume rm seat-loadcheck-gradle
docker image rm seat-loadcheck-app:1ace1d7
```
