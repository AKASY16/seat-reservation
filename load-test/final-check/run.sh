#!/usr/bin/env bash
# 최종 버전 확인 실행
#
# README.md 부하 시험의 조건으로 두 구성을 차례로 돌리고, 실행마다 원본 자료를 남긴다.
#   A. 서버 1대 + MySQL 1대
#   B. 서버 2대 + 같은 MySQL 1대
# 구성마다 예열 3회 뒤 정식 측정 3회를 한다. 정식 측정은 앞 실행의 k6가 끝나고 30초 뒤에 시작한다.
# 모든 실행 전에 전용 DB를 초기화하고, 좌석 1,000개가 모두 빈 좌석인지 확인한다.
#
# 개발용 docker-compose.yml과 다른 전용 구성(compose.yml)을 쓴다. 시작할 때 전용 구성의
# 컨테이너와 볼륨만 지우고 새로 만들며, 개발용 DB와 볼륨은 건드리지 않는다.
#
# 실행 (저장소 루트에서. bash와 Docker가 필요하고, Windows에서는 Git Bash로 실행한다)
#   load-test/final-check/run.sh
#
# JAR는 eclipse-temurin:21-jdk 컨테이너에서 빌드한다. PC에 JDK 21이 있으면 BUILD_MODE=local로 바꿀 수 있다.
# 결과는 load-test/results/final-check-<시작 시각 UTC>/ 아래에 남는다.
# 종료 코드는 모든 실행이 성공 판정이면 0, 하나라도 실패하면 1, 준비 단계에서 멈추면 2다.

set -uo pipefail
# Git Bash가 /scripts 같은 컨테이너 안 경로를 Windows 경로로 바꾸지 않게 한다.
export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")/../.." || exit 2

PROJECT=seat-loadcheck
COMPOSE_FILE=load-test/final-check/compose.yml
NETWORK=seat-loadcheck-net
HERE=load-test/final-check
K6_IMAGE=grafana/k6:2.3.0
MYSQL_IMAGE=mysql:8.4
JDK_IMAGE=eclipse-temurin:21-jdk
BUILD_MODE=${BUILD_MODE:-docker}
CONFIGS=${CONFIGS:-A B}
WARMUPS=${WARMUPS:-3}
RUNS=${RUNS:-3}
GAP_SECONDS=${GAP_SECONDS:-30}
ITERATIONS=5000
SEATS=1000
POOL_SIZE=20

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT=load-test/results/final-check-$STAMP
mkdir -p "$OUT" || exit 2

LAST_K6_END_MS=
FAILED_RUNS=0

log() {
    printf '[%s UTC] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$OUT/run.log"
}

die() {
    log "중단: $*"
    exit 2
}

compose() {
    LOADCHECK_APP_IMAGE=${APP_IMAGE:-} docker compose -p "$PROJECT" -f "$COMPOSE_FILE" "$@"
}

# Docker Desktop(Windows)에는 C:/... 형태의 경로를 넘겨야 한다.
host_path() {
    if pwd -W >/dev/null 2>&1; then (cd "$1" && pwd -W); else (cd "$1" && pwd); fi
}

# GNU date가 없는 macOS에서는 perl로 대신한다.
now_ms() {
    local t
    t=$(date +%s%3N 2>/dev/null)
    if [[ $t =~ ^[0-9]+$ ]]; then echo "$t"; else perl -MTime::HiRes=time -e 'printf "%d\n", time() * 1000'; fi
}

kst() {
    local s=$(($1 / 1000 + 9 * 3600))
    date -u -d "@$s" '+%Y-%m-%d %H:%M:%S KST' 2>/dev/null || date -u -r "$s" '+%Y-%m-%d %H:%M:%S KST'
}

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

# HEAD와 다른 애플리케이션 코드와 빌드 파일 목록 (추적하지 않는 새 파일 포함)
app_changes() {
    local paths=(src build.gradle settings.gradle gradle Dockerfile)
    git -c core.quotepath=false diff --name-only HEAD -- "${paths[@]}"
    git -c core.quotepath=false ls-files --others --exclude-standard -- "${paths[@]}"
}

is_int() {
    [[ $1 =~ ^-?[0-9]+$ ]]
}

# 전용 DB에서 표준 입력의 SQL을 실행한다. 출력은 mysql 클라이언트 그대로다.
db_sql() {
    docker exec -i -e MYSQL_PWD=root "$DB_CID" mysql -uroot seat_reservation "$@"
}

# k6-metrics.env 같은 KEY=VALUE 파일에서 값을 읽는다. 없으면 '미측정'.
getv() {
    local v
    v=$(grep -m1 "^$2=" "$1" 2>/dev/null | cut -d= -f2- | tr -d '\r')
    printf '%s' "${v:-미측정}"
}

# 컨테이너의 누적 CPU 사용 시간(마이크로초). cgroup v2와 v1을 모두 읽는다. 못 읽으면 NA.
parse_cpu() {
    awk '$1 == "usage_usec" {print $2; found = 1}
         $1 == "cpuacct_ns" && $2 ~ /^[0-9]+$/ {print int($2 / 1000); found = 1}
         END {if (!found) print "NA"}' | head -1
}

cpu_usec() {
    docker exec "$1" sh -c 'cat /sys/fs/cgroup/cpu.stat 2>/dev/null || echo "cpuacct_ns $(cat /sys/fs/cgroup/cpuacct/cpuacct.usage 2>/dev/null)"' 2>/dev/null | tr -d '\r' | parse_cpu
}

# 정해진 실행 시간 동안 평균 몇 코어를 썼는지. 둘 중 하나라도 없으면 미측정.
avg_cores() {
    local before=$1 after=$2 duration_ms=$3
    if is_int "$before" && is_int "$after" && [[ $duration_ms =~ ^[0-9.]+$ ]]; then
        awk -v b="$before" -v a="$after" -v d="$duration_ms" 'BEGIN {printf "%.2f", (a - b) / 1000 / d}'
    else
        printf '미측정'
    fi
}

# k6와 같은 네트워크에서 서비스 이름으로 응답이 오는지 본다.
probe() {
    docker run --rm --network "$NETWORK" --entrypoint wget "$K6_IMAGE" -q -O /dev/null -T 3 "http://$1:8080/performances/1/seats" >/dev/null 2>&1
}

wait_ready() {
    local i
    for i in $(seq 1 90); do
        probe "$1" && return 0
        sleep 2
    done
    return 1
}

# counters.sql 출력에서 값 하나를 꺼낸다. INNODB_METRICS 값은 enabled일 때만 쓴다.
counter() {
    awk -F'\t' -v n="$2" '$1 == n { if (NF >= 3 && $3 != "enabled") print "미측정"; else print $2; found = 1 }
                          END { if (!found) print "미측정" }' "$1" | tr -d '\r' | head -1
}

delta() {
    if is_int "$1" && is_int "$2"; then echo $(($2 - $1)); else echo 미측정; fi
}

# verify.sql 출력에서 머리글 다음 줄의 값을 꺼낸다.
verify_value() {
    awk -v h="$2" '$0 == h { getline; print; found = 1 } END { if (!found) print "미측정" }' "$1" | tr -d '\r' | head -1
}

record_environment() {
    local env=$OUT/environment.txt
    {
        echo "# 최종 버전 확인 실행 환경"
        echo
        echo "실행 시작 (UTC): $(date -u '+%Y-%m-%d %H:%M:%S')"
        echo "실행 시작 (KST): $(kst "$(now_ms)")"
        echo "결과 폴더: $OUT"
        echo
        echo "## 측정 대상 코드"
        echo "Git HEAD: $(git rev-parse HEAD)"
        echo "커밋: $(git log -1 --format='%s (%ad)' --date=iso)"
        echo "미커밋 변경 (git status --porcelain):"
        local status
        status=$(git -c core.quotepath=false status --porcelain)
        if [ -z "$status" ]; then echo "  없음"; else printf '%s\n' "$status" | sed 's/^/  /'; fi
        echo "애플리케이션 코드와 빌드 파일(src/, build.gradle, settings.gradle, gradle/, Dockerfile)의 HEAD 대비 변경:"
        local app_diff
        app_diff=$(app_changes)
        if [ -z "$app_diff" ]; then echo "  없음"; else printf '%s\n' "$app_diff" | sed 's/^/  /'; fi
        echo
        echo "## 실행 설정"
        echo "구성: $CONFIGS (A = 서버 1대, B = 서버 2대, 같은 MySQL 1대)"
        echo "구성마다 예열 ${WARMUPS}회, 정식 측정 ${RUNS}회, 정식 측정 전 간격 ${GAP_SECONDS}초"
        echo "k6: shared-iterations, VU 200, 총 ${ITERATIONS}건, 요청당 좌석 1~4개, 인기 구간 100석에 60% (load-test/hold-load.js)"
        echo "HOLD_TTL=PT5M, 서버별 커넥션 풀 ${POOL_SIZE} (compose.yml)"
        echo "CPU 제한, 인위적 네트워크 지연, 연결 한계 조정: 없음"
        echo "k6 요청 경로: 같은 Docker 네트워크($NETWORK)에서 서비스 이름으로 요청 (http://app-1:8080, http://app-2:8080)"
        echo "JAR 빌드 방식: $BUILD_MODE"
        echo
        echo "## 호스트"
        uname -a 2>/dev/null
        if command -v powershell.exe >/dev/null 2>&1; then
            powershell.exe -NoProfile -Command "(Get-CimInstance Win32_OperatingSystem).Caption; (Get-CimInstance Win32_Processor).Name; 'RAM ' + [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1) + ' GB'" 2>/dev/null | tr -d '\r'
        elif [ -r /proc/cpuinfo ]; then
            grep -m1 'model name' /proc/cpuinfo
            echo "CPU $(nproc 2>/dev/null)개, $(free -h 2>/dev/null | awk '/^Mem:/ {print "메모리 " $2}')"
        elif command -v sysctl >/dev/null 2>&1; then
            sysctl -n machdep.cpu.brand_string hw.memsize 2>/dev/null
        fi
        echo
        echo "## Docker"
        docker version --format 'Client {{.Client.Version}} / Server {{.Server.Version}}' 2>&1
        docker compose version 2>&1
        docker info --format 'Docker VM: CPU {{.NCPU}}개, 메모리 {{.MemTotal}} bytes, {{.OperatingSystem}}, 커널 {{.KernelVersion}}, cgroup v{{.CgroupVersion}}' 2>&1
        echo "실행 시작 때 이미 떠 있던 컨테이너 (측정에 함께 CPU를 쓸 수 있다):"
        local running
        running=$(docker ps --format '{{.Names}} ({{.Image}}, {{.Status}})')
        if [ -z "$running" ]; then echo "  없음"; else printf '%s\n' "$running" | sed 's/^/  /'; fi
    } > "$env"
}

build_app() {
    log "JAR 빌드 ($BUILD_MODE)"
    if [ "$BUILD_MODE" = local ]; then
        ./gradlew bootJar --no-daemon -q > "$OUT/build.log" 2>&1 || die "JAR 빌드 실패 ($OUT/build.log)"
    else
        docker pull -q "$JDK_IMAGE" >/dev/null || die "$JDK_IMAGE를 받지 못함"
        docker run --rm -v "$(host_path .):/src" -v seat-loadcheck-gradle:/gradle-home -e GRADLE_USER_HOME=/gradle-home \
            -w /src "$JDK_IMAGE" ./gradlew bootJar --no-daemon -q > "$OUT/build.log" 2>&1 || die "JAR 빌드 실패 ($OUT/build.log)"
        # 리눅스에서는 컨테이너가 만든 파일이 root 소유가 되므로 실행한 사용자에게 돌려준다.
        if [ "$(uname -s)" = Linux ] && [ "$(id -u)" != 0 ]; then
            docker run --rm -v "$(host_path .):/src" "$JDK_IMAGE" chown -R "$(id -u):$(id -g)" /src/build /src/.gradle
        fi
    fi
    [ -f build/libs/app.jar ] || die "build/libs/app.jar가 없음"

    local short tag
    short=$(git rev-parse --short HEAD)
    tag=$short
    if [ -n "$(app_changes)" ]; then tag=$short-dirty; fi
    APP_IMAGE=seat-loadcheck-app:$tag
    log "앱 이미지 빌드 ($APP_IMAGE)"
    docker build -q -t "$APP_IMAGE" . > "$OUT/image-build.log" 2>&1 || die "이미지 빌드 실패 ($OUT/image-build.log)"
    docker pull -q "$MYSQL_IMAGE" >/dev/null || die "$MYSQL_IMAGE를 받지 못함"
    docker pull -q "$K6_IMAGE" >/dev/null || die "$K6_IMAGE를 받지 못함"

    local jar_sha image_jar_sha
    jar_sha=$(sha256 build/libs/app.jar)
    image_jar_sha=$(docker run --rm --entrypoint sha256sum "$APP_IMAGE" /app/app.jar | cut -d' ' -f1)
    {
        echo
        echo "## 빌드 결과"
        echo "JAR: build/libs/app.jar, $(wc -c < build/libs/app.jar | tr -d ' ') bytes"
        echo "JAR SHA-256: $jar_sha"
        echo "이미지 안 JAR SHA-256: $image_jar_sha"
        echo "앱 이미지: $APP_IMAGE"
        echo "앱 이미지 ID: $(docker image inspect -f '{{.Id}}' "$APP_IMAGE")"
        if [ "$BUILD_MODE" != local ]; then
            echo "빌드 JDK 이미지: $JDK_IMAGE ($(docker image inspect -f '{{.Id}}' "$JDK_IMAGE"))"
        fi
        echo "앱 실행 JRE: $(docker run --rm --entrypoint java "$APP_IMAGE" -version 2>&1 | head -1)"
        echo "MySQL 이미지: $MYSQL_IMAGE ($(docker image inspect -f '{{.Id}}' "$MYSQL_IMAGE"))"
        echo "k6 이미지: $K6_IMAGE ($(docker image inspect -f '{{.Id}}' "$K6_IMAGE"))"
        echo "k6 버전: $(docker run --rm "$K6_IMAGE" version | head -1)"
    } >> "$OUT/environment.txt"
    [ "$jar_sha" = "$image_jar_sha" ] || die "이미지 안의 JAR가 방금 빌드한 JAR와 다름"
}

start_db() {
    log "전용 구성의 기존 컨테이너와 볼륨을 지우고 DB를 새로 띄움 (프로젝트 $PROJECT)"
    compose down -v --remove-orphans > "$OUT/compose.log" 2>&1
    compose up -d --wait db >> "$OUT/compose.log" 2>&1 || die "DB를 띄우지 못함 ($OUT/compose.log)"
    DB_CID=$(compose ps -q db)
    {
        echo
        echo "## MySQL"
        db_sql < "$HERE/db-settings.sql"
    } >> "$OUT/environment.txt"
}

# 구성에 맞게 서버를 띄우고, 실제로 떠 있는 서버를 기록한다.
start_apps() {
    local config=$1 dir=$OUT/$1
    mkdir -p "$dir"
    if [ "$config" = A ]; then
        log "구성 A: app-1만 띄우고 app-2는 중지"
        compose up -d --no-deps --force-recreate app-1 >> "$OUT/compose.log" 2>&1 || die "app-1을 띄우지 못함"
        compose stop app-2 >> "$OUT/compose.log" 2>&1
        BASE_URLS=http://app-1:8080
        APP_SERVICES=(app-1)
    else
        log "구성 B: app-1, app-2를 새로 띄움"
        compose up -d --no-deps --force-recreate app-1 app-2 >> "$OUT/compose.log" 2>&1 || die "서버를 띄우지 못함"
        BASE_URLS=http://app-1:8080,http://app-2:8080
        APP_SERVICES=(app-1 app-2)
    fi
    local svc
    for svc in "${APP_SERVICES[@]}"; do
        wait_ready "$svc" || die "$svc가 응답하지 않음"
    done

    {
        echo "# 구성 $config 서버 상태"
        echo
        echo "k6 BASE_URLS: $BASE_URLS"
        echo
        echo "## docker compose ps -a"
        compose ps -a --format 'table {{.Service}}\t{{.State}}\t{{.Status}}\t{{.Image}}'
        echo
        echo "## 서버 컨테이너"
        for svc in app-1 app-2; do
            local id
            id=$(compose ps -a -q "$svc")
            if [ -z "$id" ]; then
                echo "$svc: 컨테이너 없음"
                continue
            fi
            docker inspect -f "$svc: 상태={{.State.Status}} 이미지={{.Image}} NanoCpus={{.HostConfig.NanoCpus}} CpuQuota={{.HostConfig.CpuQuota}} 메모리제한={{.HostConfig.Memory}} IP={{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}" "$id"
            docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$id" | grep -E '^(DB_POOL_SIZE|HOLD_TTL|DB_URL)=' | sed "s/^/  $svc /"
        done
        echo
        echo "## k6 네트워크에서 서비스 이름으로 접근"
        for svc in app-1 app-2; do
            if probe "$svc"; then echo "$svc: 응답함"; else echo "$svc: 응답 없음"; fi
        done
    } > "$dir/servers.txt"

    if [ "$config" = A ]; then
        if [ -n "$(compose ps -q --status running app-2)" ] || probe app-2; then
            die "구성 A인데 app-2가 실행 중"
        fi
    fi
}

run_one() {
    local config=$1 kind=$2 index=$3
    local name=$kind-$index
    local dir=$OUT/$config/$name
    mkdir -p "$dir"
    log "구성 $config $name 시작"

    # 1. 전용 DB 초기화와 실행 전 확인
    local ready=ok
    db_sql < load-test/reset.sql > "$dir/reset.txt" 2>&1 || ready="초기화 실패"
    db_sql < "$HERE/ready-check.sql" > "$dir/ready-check.txt" 2>&1 || ready="실행 전 확인 실패"
    local seats available rest
    seats=$(awk -F'\t' 'NR == 2 {print $1}' "$dir/ready-check.txt" | tr -d '\r')
    available=$(awk -F'\t' 'NR == 2 {print $2}' "$dir/ready-check.txt" | tr -d '\r')
    rest=$(awk -F'\t' 'NR == 2 {print $3 + $4 + $5 + $6 + $7}' "$dir/ready-check.txt" | tr -d '\r')
    if [ "$seats" != "$SEATS" ] || [ "$available" != "$SEATS" ] || [ "$rest" != 0 ]; then
        ready="좌석 $seats개 중 빈 좌석 $available개, 남은 기록 $rest건"
    fi

    # 2. 정식 측정은 앞 실행의 k6가 끝나고 GAP_SECONDS가 지난 뒤 시작한다.
    if [ "$kind" = run ] && [ -n "$LAST_K6_END_MS" ]; then
        local wait_ms=$((LAST_K6_END_MS + GAP_SECONDS * 1000 - $(now_ms)))
        if [ "$wait_ms" -gt 0 ]; then
            sleep "$(awk -v w="$wait_ms" 'BEGIN {printf "%.3f", w / 1000}')"
        fi
    fi

    # 3. 수집 경계: 카운터를 읽고 문장 통계를 비운다. 정식 측정과 예열 모두 같은 순서로 한다.
    # 서비스 순서: 0 = db, 1 = app-1, 2 = app-2
    local services=(db "${APP_SERVICES[@]}") cids=() cpu_before=() cpu_after=() app_lines=() i
    for i in "${!services[@]}"; do
        cids[$i]=$(docker ps -q --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.service=${services[$i]}")
    done
    db_sql -N -B < "$HERE/counters.sql" > "$dir/db-counters-before.tsv" 2>&1
    db_sql < "$HERE/stats-boundary.sql" >> "$dir/db-counters-before.tsv" 2>&1
    for i in "${!services[@]}"; do
        cpu_before[$i]=$(cpu_usec "${cids[$i]}")
        if [ "$i" -gt 0 ]; then app_lines[$i]=$(docker logs "${cids[$i]}" 2>&1 | wc -l | tr -d ' '); fi
    done

    # 4. k6
    docker rm -f "$PROJECT-k6" >/dev/null 2>&1
    local k6_cmd=(docker run --rm --name "$PROJECT-k6" --network "$NETWORK" --user "$(id -u):$(id -g)"
        -v "$(host_path load-test):/scripts:ro" -v "$(host_path "$dir"):/out"
        -e "BASE_URLS=$BASE_URLS" -e SUMMARY_DIR=/out
        --entrypoint sh "$K6_IMAGE" /scripts/final-check/k6-run.sh)
    {
        printf '%q ' "${k6_cmd[@]}"
        echo
    } > "$dir/command.txt"
    local start_ms end_ms k6_exit
    start_ms=$(now_ms)
    "${k6_cmd[@]}" > "$dir/k6.log" 2>&1
    k6_exit=$?
    end_ms=$(now_ms)

    # 5. 측정 직후: 문장 통계, 카운터, CPU를 먼저 읽고 나서 정합성 검사를 한다.
    db_sql < "$HERE/statements-by-kind.sql" > "$dir/statements-by-kind.tsv" 2>&1
    db_sql < "$HERE/statements-by-digest.sql" > "$dir/statements-by-digest.tsv" 2>&1
    db_sql -N -B < "$HERE/counters.sql" > "$dir/db-counters-after.tsv" 2>&1
    for i in "${!services[@]}"; do
        cpu_after[$i]=$(cpu_usec "${cids[$i]}")
        if [ "$i" -gt 0 ]; then docker logs "${cids[$i]}" 2>&1 | tail -n +$((app_lines[$i] + 1)) > "$dir/${services[$i]}.log"; fi
    done
    db_sql < load-test/verify.sql > "$dir/verify.txt" 2>&1
    db_sql < "$HERE/connections.sql" > "$dir/connections.tsv" 2>&1
    db_sql -e 'SHOW ENGINE INNODB STATUS\G' > "$dir/innodb-status.txt" 2>&1

    # 6. 값 정리
    local m=$dir/k6-metrics.env
    local requests complete interrupted s201 s409 unexpected transport passes fails thresholds
    requests=$(getv "$m" K6_REQUESTS)
    complete=$(getv "$m" K6_ITERATIONS_COMPLETE)
    interrupted=$(grep -oE '[0-9]+ complete and [0-9]+ interrupted iterations' "$dir/k6.log" | tail -1 | awk '{print $4}')
    interrupted=${interrupted:-미측정}
    s201=$(getv "$m" K6_STATUS_201)
    s409=$(getv "$m" K6_STATUS_409)
    unexpected=$(getv "$m" K6_UNEXPECTED_STATUS)
    transport=$(getv "$m" K6_TRANSPORT_ERRORS)
    passes=$(getv "$m" K6_CHECKS_PASSES)
    fails=$(getv "$m" K6_CHECKS_FAILS)
    thresholds=$(getv "$m" K6_THRESHOLDS_OK)

    local v1 v2 v3 held_seats holds
    v1=$(verify_value "$dir/verify.txt" seats_with_multiple_active_holds)
    v2=$(verify_value "$dir/verify.txt" partially_held_holds)
    v3=$(verify_value "$dir/verify.txt" orphan_held_seats)
    held_seats=$(awk -F'\t' '$1 == "held_seats" { getline; print $1 }' "$dir/verify.txt" | tr -d '\r')
    holds=$(awk -F'\t' '$1 == "held_seats" { getline; print $3 }' "$dir/verify.txt" | tr -d '\r')

    local b=$dir/db-counters-before.tsv a=$dir/db-counters-after.tsv
    local deadlocks lock_timeouts lock_waits lock_time
    deadlocks=$(delta "$(counter "$b" lock_deadlocks)" "$(counter "$a" lock_deadlocks)")
    lock_timeouts=$(delta "$(counter "$b" lock_timeouts)" "$(counter "$a" lock_timeouts)")
    lock_waits=$(delta "$(counter "$b" Innodb_row_lock_waits)" "$(counter "$a" Innodb_row_lock_waits)")
    lock_time=$(delta "$(counter "$b" Innodb_row_lock_time)" "$(counter "$a" Innodb_row_lock_time)")

    local test_ms k6_cpu_before k6_cpu_after cpu_db cpu_app1 cpu_app2 cpu_k6
    test_ms=$(getv "$m" K6_TEST_DURATION_MS)
    k6_cpu_before=$(tr -d '\r' < "$dir/k6-cpu-before.txt" 2>/dev/null | parse_cpu)
    k6_cpu_after=$(tr -d '\r' < "$dir/k6-cpu-after.txt" 2>/dev/null | parse_cpu)
    cpu_db=$(avg_cores "${cpu_before[0]}" "${cpu_after[0]}" "$test_ms")
    cpu_app1=$(avg_cores "${cpu_before[1]}" "${cpu_after[1]}" "$test_ms")
    if [ "$config" = B ]; then cpu_app2=$(avg_cores "${cpu_before[2]}" "${cpu_after[2]}" "$test_ms"); else cpu_app2=중지; fi
    cpu_k6=$(avg_cores "$k6_cpu_before" "$k6_cpu_after" "$test_ms")
    {
        echo "CPU 사용 시간 (마이크로초, 컨테이너 cgroup 누적값)"
        for i in "${!services[@]}"; do
            echo "${services[$i]} 전 ${cpu_before[$i]} 후 ${cpu_after[$i]}"
        done
        echo "k6 전 $k6_cpu_before 후 $k6_cpu_after"
        echo "k6 시험 시간 $test_ms ms"
        echo "평균 코어 수 = (후 - 전) / k6 시험 시간. 앞뒤 값을 읽는 시점이 시험 시간보다 조금 넓어서 실제보다 약간 크게 나올 수 있다."
        echo "db $cpu_db, app-1 $cpu_app1, app-2 $cpu_app2, k6 $cpu_k6"
    } > "$dir/cpu.txt"

    local app_errors=0 pool_timeouts=0 n svc
    for svc in "${APP_SERVICES[@]}"; do
        n=$(grep -cE ' (ERROR|WARN) ' "$dir/$svc.log")
        app_errors=$((app_errors + n))
        n=$(grep -c 'Connection is not available' "$dir/$svc.log")
        pool_timeouts=$((pool_timeouts + n))
    done

    local gap=-
    if [ -n "$LAST_K6_END_MS" ]; then gap=$(awk -v s="$start_ms" -v e="$LAST_K6_END_MS" 'BEGIN {printf "%.1f", (s - e) / 1000}'); fi
    LAST_K6_END_MS=$end_ms

    # 7. 판정. k6 종료 코드만 보지 않고 값을 하나씩 확인한다.
    local reasons=()
    [ "$ready" = ok ] || reasons+=("실행 전 상태: $ready")
    [ "$requests" = "$ITERATIONS" ] || reasons+=("요청 ${requests}건")
    [ "$complete" = "$ITERATIONS" ] || reasons+=("완료한 반복 ${complete}회")
    [ "$interrupted" = 0 ] || reasons+=("중단된 반복 ${interrupted}회")
    if is_int "$s201" && is_int "$s409"; then
        [ $((s201 + s409)) -eq "$ITERATIONS" ] || reasons+=("201+409 = $((s201 + s409))")
    else
        reasons+=("201, 409 미측정")
    fi
    [ "$unexpected" = 0 ] || reasons+=("예상 밖 HTTP 응답 ${unexpected}건")
    [ "$transport" = 0 ] || reasons+=("전송 오류 ${transport}건")
    { [ "$passes" = "$ITERATIONS" ] && [ "$fails" = 0 ]; } || reasons+=("check 성공 ${passes}, 실패 ${fails}")
    { [ "$v1" = 0 ] && [ "$v2" = 0 ] && [ "$v3" = 0 ]; } || reasons+=("verify.sql $v1/$v2/$v3")
    [ "$deadlocks" = 0 ] || reasons+=("데드락 증가 $deadlocks")
    [ "$thresholds" = true ] || reasons+=("k6 threshold 실패")
    local verdict=성공
    if [ ${#reasons[@]} -gt 0 ]; then
        verdict=실패
        FAILED_RUNS=$((FAILED_RUNS + 1))
    fi
    local reason_text
    reason_text=$(IFS=';'; printf '%s' "${reasons[*]:-}")

    local popular popular_pct
    popular=$(getv "$m" K6_POPULAR_REQUESTS)
    popular_pct=$(awk -v p="$popular" -v r="$requests" 'BEGIN {if (r + 0 > 0 && p ~ /^[0-9]+$/) printf "%.1f", p * 100 / r; else printf "미측정"}')
    local failed_pct unexpected_pct
    failed_pct=$(awk -v v="$(getv "$m" K6_HTTP_REQ_FAILED_RATE)" 'BEGIN {if (v ~ /^[0-9.e-]+$/) printf "%.2f", v * 100; else printf "미측정"}')
    unexpected_pct=$(awk -v v="$(getv "$m" K6_UNEXPECTED_RATE)" 'BEGIN {if (v ~ /^[0-9.e-]+$/) printf "%.2f", v * 100; else printf "미측정"}')

    # runs.tsv의 열 순서는 summarize.sh와 맞춘다.
    local row=(
        "$config" "$kind" "$index" "$(kst "$start_ms")" "$gap"
        "$requests" "$complete" "$interrupted" "$s201" "$s409" "$unexpected" "$transport"
        "$passes" "$fails" "$popular" "$popular_pct"
        "$(getv "$m" K6_REQ_PER_SEC)" "$test_ms"
        "$(getv "$m" K6_DURATION_AVG_MS)" "$(getv "$m" K6_DURATION_MED_MS)" "$(getv "$m" K6_DURATION_P95_MS)" "$(getv "$m" K6_DURATION_MAX_MS)"
        "$(getv "$m" K6_CREATED_AVG_MS)" "$(getv "$m" K6_CREATED_MED_MS)" "$(getv "$m" K6_CREATED_P95_MS)" "$(getv "$m" K6_CREATED_MAX_MS)"
        "$(getv "$m" K6_CONFLICTED_AVG_MS)" "$(getv "$m" K6_CONFLICTED_MED_MS)" "$(getv "$m" K6_CONFLICTED_P95_MS)" "$(getv "$m" K6_CONFLICTED_MAX_MS)"
        "$failed_pct" "$unexpected_pct"
        "$v1" "$v2" "$v3" "${held_seats:-미측정}" "${holds:-미측정}"
        "$deadlocks" "$lock_timeouts" "$lock_waits" "$lock_time"
        "$cpu_db" "$cpu_app1" "$cpu_app2" "$cpu_k6"
        "$(getv "$m" K6_SERVER_1_REQUESTS)" "$(getv "$m" K6_SERVER_2_REQUESTS)"
        "$app_errors" "$pool_timeouts" "$thresholds" "$k6_exit" "$verdict" "${reason_text:--}" "$config/$name"
    )
    (IFS=$'\t'; printf '%s\n' "${row[*]}") >> "$OUT/runs.tsv"
    {
        echo "판정: $verdict"
        [ -n "$reason_text" ] && echo "실패 이유: $reason_text"
        echo "k6 종료 코드: $k6_exit"
        echo "k6 시작: $(kst "$start_ms"), 끝: $(kst "$end_ms"), 앞 실행의 k6 종료부터 간격: ${gap}초"
    } > "$dir/verdict.txt"
    log "구성 $config $name: $verdict (요청 $requests, 201 $s201, 409 $s409, 예상 밖 $unexpected, 전송 오류 $transport, verify $v1/$v2/$v3, 데드락 증가 $deadlocks)${reason_text:+ - $reason_text}"
}

main() {
    for tool in docker git awk; do
        command -v "$tool" >/dev/null 2>&1 || die "$tool 명령이 없음"
    done
    docker compose version >/dev/null 2>&1 || die "docker compose를 쓸 수 없음"

    log "결과 폴더: $OUT"
    record_environment
    build_app
    start_db

    printf '%s\n' "config	kind	index	k6_start	gap_s	requests	complete	interrupted	status_201	status_409	unexpected_status	transport_errors	checks_passes	checks_fails	popular_requests	popular_pct	req_per_sec	test_ms	avg_ms	med_ms	p95_ms	max_ms	created_avg_ms	created_med_ms	created_p95_ms	created_max_ms	conflicted_avg_ms	conflicted_med_ms	conflicted_p95_ms	conflicted_max_ms	http_req_failed_pct	unexpected_pct	verify_multiple_holds	verify_partial_holds	verify_orphan_seats	held_seats	holds	deadlocks_delta	lock_timeouts_delta	row_lock_waits_delta	row_lock_time_ms_delta	cpu_db	cpu_app1	cpu_app2	cpu_k6	server1_requests	server2_requests	app_error_lines	pool_timeouts	thresholds_ok	k6_exit	verdict	reasons	dir" > "$OUT/runs.tsv"

    local config i
    for config in $CONFIGS; do
        start_apps "$config"
        LAST_K6_END_MS=
        for i in $(seq 1 "$WARMUPS"); do run_one "$config" warmup "$i"; done
        for i in $(seq 1 "$RUNS"); do run_one "$config" run "$i"; done
    done

    log "전용 구성 컨테이너를 멈춤 (볼륨은 남겨 둠)"
    compose stop >> "$OUT/compose.log" 2>&1

    bash "$HERE/summarize.sh" "$OUT" || log "결과표를 만들지 못함"
    if [ "$FAILED_RUNS" -gt 0 ]; then
        log "실패한 실행 ${FAILED_RUNS}회. 결과: $OUT/summary.md"
        exit 1
    fi
    log "모든 실행 성공. 결과: $OUT/summary.md"
}

main
