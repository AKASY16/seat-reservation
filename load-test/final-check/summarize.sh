#!/usr/bin/env bash
# run.sh가 남긴 runs.tsv와 실행별 원본 자료로 결과표(summary.md)를 만든다.
#   load-test/final-check/summarize.sh load-test/results/final-check-<시각>
#
# 실행 하나가 표의 한 행이다. 구성별 범위 행은 정식 측정 값의 최소~최대이며, 여러 실행의 평균은 내지 않는다.

set -uo pipefail

OUT=${1:?결과 폴더를 넘겨야 한다}
RUNS=$OUT/runs.tsv
SUMMARY=$OUT/summary.md
[ -f "$RUNS" ] || { echo "$RUNS가 없음" >&2; exit 1; }

env_value() {
    grep -m1 "^$1" "$OUT/environment.txt" 2>/dev/null | cut -d: -f2- | sed 's/^ //'
}

# 실행별 statements-by-kind.tsv를 "구성 실행 종류 횟수 누적ms" 한 줄씩으로 모은다.
kinds() {
    awk -F'\t' 'NR > 1 && $2 == "run" {print $1 "\t" $3 "\t" $NF}' "$RUNS" | while IFS=$'\t' read -r config index dir; do
        # 열: HOST, EVENT_NAME, COUNT_STAR, total_ms, ... 서버가 두 대면 서버마다 행이 따로 있어서 더한다.
        awk -F'\t' -v c="$config" -v i="$index" 'NR > 1 && $3 ~ /^[0-9]+$/ {print c "\t" i "\t" $2 "\t" $3 "\t" $4}' "$OUT/$dir/statements-by-kind.tsv" 2>/dev/null
    done
}

{
    echo "# 최종 버전 확인 실행 결과"
    echo
    echo "- 측정 대상 Git HEAD: \`$(env_value 'Git HEAD')\`"
    echo "- JAR SHA-256: \`$(env_value 'JAR SHA-256')\`"
    echo "- 앱 이미지 ID: \`$(env_value '앱 이미지 ID')\`"
    echo "- 실행 시작: $(env_value '실행 시작 (KST)')"
    echo "- 환경과 설정 전체: [environment.txt](environment.txt), 실행 기록: [run.log](run.log), 한 행씩 정리한 원본 값: [runs.tsv](runs.tsv)"
    echo
    echo "성공 판정: 요청 5,000건 완료, 중단 0, 201 + 409 = 5,000, 예상 밖 HTTP 응답과 전송 오류 0, check 100%, verify.sql 세 값 모두 0, 측정 구간의 데드락 증가 0, k6 threshold 통과."
    echo "'미측정'은 값을 얻지 못했다는 뜻이고 0으로 채우지 않았습니다. 응답 시간 단위는 ms입니다."
    echo

    awk -F'\t' '
    function commas(n,    s, out) {
        if (n !~ /^[0-9]+$/) return n
        s = n; out = ""
        while (length(s) > 3) { out = "," substr(s, length(s) - 2) out; s = substr(s, 1, length(s) - 3) }
        return s out
    }
    function num(v) { return v ~ /^-?[0-9]+(\.[0-9]+)?$/ }
    function fmt1(v) { return num(v) ? sprintf("%.1f", v) : v }
    function rps(v) { return num(v) ? commas(sprintf("%d", v + 0.5)) : v }
    function link(row) { return "[" row["config"] " " row["kind"] "-" row["index"] "](" row["dir"] "/)" }
    function track(config, key, v) {
        if (!num(v)) { bad[config, key] = 1; return }
        if (!((config, key) in lo) || v + 0 < lo[config, key]) lo[config, key] = v + 0
        if (!((config, key) in hi) || v + 0 > hi[config, key]) hi[config, key] = v + 0
    }
    function range(config, key, f) {
        if ((config, key) in bad || !((config, key) in lo)) return "-"
        if (f == "rps") return rps(lo[config, key]) "~" rps(hi[config, key])
        if (f == "int") return commas(lo[config, key]) "~" commas(hi[config, key])
        return fmt1(lo[config, key]) "~" fmt1(hi[config, key])
    }
    NR == 1 { for (i = 1; i <= NF; i++) col[i] = $i; next }
    {
        n++
        for (i = 1; i <= NF; i++) r[n, col[i]] = $i
        if (!($1 in seen)) { seen[$1] = 1; configs[++nc] = $1 }
    }
    END {
        print "## 정식 측정: 정합성"
        print ""
        print "| 구성 | 실행 | 요청 | 완료 / 중단 반복 | 201 | 409 | 예상 밖 HTTP 응답 | 전송 오류 | check 성공 | verify.sql (중복 / 부분 / 고아) | 데드락 증가 | 판정 |"
        print "|---|---|---|---|---|---|---|---|---|---|---|---|"
        for (k = 1; k <= n; k++) {
            if (r[k, "kind"] != "run") continue
            for (c in col) row[col[c]] = r[k, col[c]]
            printf "| %s | %s | %s | %s / %s | %s | %s | %s | %s | %s / %s | %s / %s / %s | %s | %s |\n", \
                row["config"], link(row), commas(row["requests"]), commas(row["complete"]), row["interrupted"], \
                commas(row["status_201"]), commas(row["status_409"]), row["unexpected_status"], row["transport_errors"], \
                commas(row["checks_passes"]), commas(row["requests"]), \
                row["verify_multiple_holds"], row["verify_partial_holds"], row["verify_orphan_seats"], row["deadlocks_delta"], row["verdict"]
        }
        print ""
        print "## 정식 측정: 처리량과 응답 시간"
        print ""
        print "| 구성 | 실행 | k6 시작 | 앞 실행 k6 종료부터 간격 | 인기 구간 요청 (비율) | 처리량 (req/s) | 평균 | 중앙값 | p95 | 최대 | 기본 http_req_failed (409 포함) | 예상 밖 응답 비율 |"
        print "|---|---|---|---|---|---|---|---|---|---|---|---|"
        for (ci = 1; ci <= nc; ci++) {
            cfg = configs[ci]; m = 0
            for (k = 1; k <= n; k++) {
                if (r[k, "kind"] != "run" || r[k, "config"] != cfg) continue
                for (c in col) row[col[c]] = r[k, col[c]]
                m++
                printf "| %s | %s | %s | %s초 | %s (%s%%) | %s | %s | %s | %s | %s | %s%% | %s%% |\n", \
                    cfg, link(row), row["k6_start"], row["gap_s"], commas(row["popular_requests"]), row["popular_pct"], \
                    rps(row["req_per_sec"]), fmt1(row["avg_ms"]), fmt1(row["med_ms"]), fmt1(row["p95_ms"]), fmt1(row["max_ms"]), \
                    row["http_req_failed_pct"], row["unexpected_pct"]
                track(cfg, "rps", row["req_per_sec"]); track(cfg, "avg", row["avg_ms"]); track(cfg, "med", row["med_ms"])
                track(cfg, "p95", row["p95_ms"]); track(cfg, "max", row["max_ms"])
                track(cfg, "s201", row["status_201"]); track(cfg, "s409", row["status_409"])
                track(cfg, "lw", row["row_lock_waits_delta"]); track(cfg, "lt", row["row_lock_time_ms_delta"])
            }
            if (m > 0)
                printf "| %s | 범위 (%d회 최소~최대) | | | | %s | %s | %s | %s | %s | | |\n", cfg, m, \
                    range(cfg, "rps", "rps"), range(cfg, "avg"), range(cfg, "med"), range(cfg, "p95"), range(cfg, "max")
        }
        print ""
        print "## 정식 측정: 201과 409의 응답 시간"
        print ""
        print "| 구성 | 실행 | 201 건수 | 201 평균 | 201 중앙값 | 201 p95 | 201 최대 | 409 건수 | 409 평균 | 409 중앙값 | 409 p95 | 409 최대 |"
        print "|---|---|---|---|---|---|---|---|---|---|---|---|"
        for (k = 1; k <= n; k++) {
            if (r[k, "kind"] != "run") continue
            for (c in col) row[col[c]] = r[k, col[c]]
            printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n", row["config"], link(row), \
                commas(row["status_201"]), fmt1(row["created_avg_ms"]), fmt1(row["created_med_ms"]), fmt1(row["created_p95_ms"]), fmt1(row["created_max_ms"]), \
                commas(row["status_409"]), fmt1(row["conflicted_avg_ms"]), fmt1(row["conflicted_med_ms"]), fmt1(row["conflicted_p95_ms"]), fmt1(row["conflicted_max_ms"])
        }
        print ""
        print "## 정식 측정: DB 잠금, 서버별 요청, CPU"
        print ""
        print "| 구성 | 실행 | 행 잠금 대기 증가 | 행 잠금 대기 시간 증가 (ms) | 잠금 대기 시간 초과 증가 | 서버별 요청 (app-1 / app-2) | 평균 CPU 코어 (db / app-1 / app-2 / k6) | 서버 ERROR·WARN 로그 | 커넥션 풀 시간 초과 로그 |"
        print "|---|---|---|---|---|---|---|---|---|"
        for (k = 1; k <= n; k++) {
            if (r[k, "kind"] != "run") continue
            for (c in col) row[col[c]] = r[k, col[c]]
            s2 = row["server2_requests"]; if (row["config"] == "A") s2 = "-"
            printf "| %s | %s | %s | %s | %s | %s / %s | %s / %s / %s / %s | %s | %s |\n", row["config"], link(row), \
                commas(row["row_lock_waits_delta"]), commas(row["row_lock_time_ms_delta"]), row["lock_timeouts_delta"], \
                commas(row["server1_requests"]), commas(s2), row["cpu_db"], row["cpu_app1"], row["cpu_app2"], row["cpu_k6"], \
                row["app_error_lines"], row["pool_timeouts"]
        }
        for (ci = 1; ci <= nc; ci++) {
            cfg = configs[ci]
            if ((cfg, "lw") in lo || (cfg, "lw") in bad)
                printf "| %s | 범위 (최소~최대) | %s | %s | | | | | |\n", cfg, range(cfg, "lw", "int"), range(cfg, "lt", "int")
        }
        print ""
        print "## 예열"
        print ""
        print "| 구성 | 실행 | 요청 | 201 | 409 | 예상 밖 HTTP 응답 | 전송 오류 | verify.sql | 데드락 증가 | 처리량 (req/s) | 판정 |"
        print "|---|---|---|---|---|---|---|---|---|---|---|"
        for (k = 1; k <= n; k++) {
            if (r[k, "kind"] != "warmup") continue
            for (c in col) row[col[c]] = r[k, col[c]]
            printf "| %s | %s | %s | %s | %s | %s | %s | %s / %s / %s | %s | %s | %s |\n", row["config"], link(row), \
                commas(row["requests"]), commas(row["status_201"]), commas(row["status_409"]), row["unexpected_status"], row["transport_errors"], \
                row["verify_multiple_holds"], row["verify_partial_holds"], row["verify_orphan_seats"], row["deadlocks_delta"], rps(row["req_per_sec"]), row["verdict"]
        }
        print ""
        print "## 실패한 실행"
        print ""
        failed = 0
        for (k = 1; k <= n; k++) {
            if (r[k, "verdict"] == "성공") continue
            failed++
            printf "- %s %s-%s: %s\n", r[k, "config"], r[k, "kind"], r[k, "index"], r[k, "reasons"]
        }
        if (!failed) print "없음"
    }' "$RUNS"

    echo
    echo "## 정식 측정: 문장 종류별 실행 수와 누적 시간 (app 계정)"
    echo
    echo "각 칸은 \"실행 수 / 누적 ms\"이고, 서버가 두 대면 두 서버의 값을 더했습니다. 만료 스케줄러의 조회도 같은 계정이라 함께 들어 있습니다."
    echo "서버별 값은 실행 폴더의 statements-by-kind.tsv에, 쿼리 형태별 값은 statements-by-digest.tsv에 있습니다. statements-by-digest.tsv는 계정을 가리지 않아 수집 경계의 TRUNCATE 같은 관측 쿼리도 들어 있습니다."
    echo
    kinds | awk -F'\t' '
    function commas(n,    s, out) {
        s = sprintf("%d", n); out = ""
        while (length(s) > 3) { out = "," substr(s, length(s) - 2) out; s = substr(s, 1, length(s) - 3) }
        return s out
    }
    function short(e) {
        if (e == "statement/sql/select") return "SELECT"
        if (e == "statement/sql/update") return "UPDATE"
        if (e == "statement/sql/insert") return "INSERT"
        if (e == "statement/sql/commit") return "COMMIT"
        if (e == "statement/sql/rollback") return "ROLLBACK"
        if (e == "statement/sql/set_option") return "SET"
        return "그 밖"
    }
    {
        key = $1 "\t" $2
        if (!(key in seen)) { seen[key] = 1; order[++n] = key }
        k = short($3)
        cnt[key, k] += $4; ms[key, k] += $5
    }
    END {
        kinds_list[1] = "SELECT"; kinds_list[2] = "UPDATE"; kinds_list[3] = "INSERT"; kinds_list[4] = "COMMIT"
        kinds_list[5] = "ROLLBACK"; kinds_list[6] = "SET"; kinds_list[7] = "그 밖"
        print "| 구성 | 실행 | SELECT | UPDATE | INSERT | COMMIT | ROLLBACK | SET | 그 밖 |"
        print "|---|---|---|---|---|---|---|---|---|"
        for (i = 1; i <= n; i++) {
            split(order[i], p, "\t")
            line = "| " p[1] " | run-" p[2]
            for (j = 1; j <= 7; j++) {
                k = kinds_list[j]
                if ((order[i], k) in cnt) line = line " | " commas(cnt[order[i], k]) " / " commas(ms[order[i], k] + 0.5)
                else line = line " | 0"
            }
            print line " |"
        }
        if (n == 0) print "| 미측정 | | | | | | | | |"
    }'
} > "$SUMMARY"

echo "결과표: $SUMMARY"
