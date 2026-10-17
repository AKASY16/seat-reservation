// 좌석 선점 부하 시험
//
// 조건
//   - 공연 1개, 좌석 1,000석 (V2 마이그레이션의 샘플 공연)
//   - 동시 요청 200 (VU 200), 총 5,000건
//   - 요청마다 좌석 1~4개
//   - 요청의 60%는 앞 두 열(A, B열, 좌석 id 1~100)을 노린다
//
// 이 시나리오에서 409(이미 잡힌 좌석)는 정상적인 충돌 응답이다.
// k6 기본 지표인 http_req_failed는 409도 실패로 세므로, 201과 409가 아닌 응답과 전송 오류는
// unexpected_responses로 따로 센다. threshold는 이 값과 check, 완료된 반복 수로 판정한다.
//
// 실행: README.md의 "부하 시험" 참고

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URLS = (__ENV.BASE_URLS || 'http://localhost:8080').split(',');
const PERFORMANCE_ID = 1;
const POPULAR_SEATS = 100;
const TOTAL_SEATS = 1000;
const POPULAR_RATIO = 0.6;
const VUS = 200;
const ITERATIONS = 5000;

const held = new Counter('holds_created');
const conflicted = new Counter('holds_conflicted');
// 201, 409가 아닌 HTTP 응답
const unexpected = new Counter('holds_unexpected_status');
// 응답을 받지 못한 요청 (연결 실패, 시간 초과 등)
const transportErrors = new Counter('transport_errors');
const unexpectedResponses = new Rate('unexpected_responses');
const popularRequests = new Counter('requests_popular');
const createdDuration = new Trend('duration_created', true);
const conflictedDuration = new Trend('duration_conflicted', true);
const requestsByServer = BASE_URLS.map((_, i) => new Counter(`requests_server_${i + 1}`));

export const options = {
    scenarios: {
        holds: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: ITERATIONS,
            maxDuration: '5m',
        },
    },
    thresholds: {
        checks: ['rate==1'],
        iterations: [`count==${ITERATIONS}`],
        unexpected_responses: ['rate==0'],
        holds_unexpected_status: ['count==0'],
        transport_errors: ['count==0'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
};

function randomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

// 연속된 좌석 묶음을 고른다. 실제 예매에서도 옆자리를 함께 잡는 경우가 많다.
function pickSeats() {
    const count = randomInt(1, 4);
    const popular = Math.random() < POPULAR_RATIO;
    const first = popular ? 1 : POPULAR_SEATS + 1;
    const last = popular ? POPULAR_SEATS : TOTAL_SEATS;
    const start = randomInt(first, last - count + 1);
    const seats = [];
    for (let i = 0; i < count; i++) {
        seats.push(start + i);
    }
    return { seats, popular };
}

export default function () {
    const { seats, popular } = pickSeats();
    // 서버가 여러 대면 요청을 번갈아 보낸다.
    const server = (__VU + __ITER) % BASE_URLS.length;
    const baseUrl = BASE_URLS[server];

    const res = http.post(
        `${baseUrl}/holds`,
        JSON.stringify({ performanceId: PERFORMANCE_ID, seatIds: seats }),
        {
            headers: {
                'Content-Type': 'application/json',
                'X-User-Id': `vu-${__VU}-${__ITER}`,
            },
            tags: { zone: popular ? 'popular' : 'normal' },
        },
    );

    requestsByServer[server].add(1);
    if (popular) {
        popularRequests.add(1);
    }

    if (res.status === 201) {
        held.add(1);
        createdDuration.add(res.timings.duration);
    } else if (res.status === 409) {
        conflicted.add(1);
        conflictedDuration.add(res.timings.duration);
    } else if (res.status === 0) {
        transportErrors.add(1);
    } else {
        unexpected.add(1);
    }
    unexpectedResponses.add(res.status !== 201 && res.status !== 409);

    check(res, {
        '201 또는 409': (r) => r.status === 201 || r.status === 409,
    });
}

// 끝난 뒤 요약을 출력한다. SUMMARY_DIR을 넘기면 k6 요약 JSON과 실행 보조 스크립트가 읽을 값도 그 폴더에 남긴다.
export function handleSummary(data) {
    const m = data.metrics;
    // 직접 정의한 카운터는 한 번도 더해지지 않으면 요약에 나오지 않는다. 이때는 실제로 0건이다.
    const count = (name) => (m[name] ? m[name].values.count : 0);
    const trend = (name) => (m[name] ? m[name].values : null);
    const ms = (v) => (v === undefined || v === null ? '없음' : v.toFixed(2));

    const requests = count('http_reqs');
    const popular = count('requests_popular');
    const all = trend('http_req_duration');
    const created = trend('duration_created');
    const conflictedValues = trend('duration_conflicted');
    const checks = m.checks ? m.checks.values : { passes: 0, fails: 0, rate: 0 };

    const values = {
        K6_TEST_DURATION_MS: data.state.testRunDurationMs.toFixed(1),
        K6_REQUESTS: requests,
        K6_ITERATIONS_COMPLETE: count('iterations'),
        K6_DROPPED_ITERATIONS: count('dropped_iterations'),
        K6_STATUS_201: count('holds_created'),
        K6_STATUS_409: count('holds_conflicted'),
        K6_UNEXPECTED_STATUS: count('holds_unexpected_status'),
        K6_TRANSPORT_ERRORS: count('transport_errors'),
        K6_UNEXPECTED_RATE: m.unexpected_responses ? m.unexpected_responses.values.rate : '없음',
        K6_HTTP_REQ_FAILED_RATE: m.http_req_failed ? m.http_req_failed.values.rate : '없음',
        K6_CHECKS_PASSES: checks.passes,
        K6_CHECKS_FAILS: checks.fails,
        K6_CHECKS_RATE: checks.rate,
        K6_POPULAR_REQUESTS: popular,
        K6_REQ_PER_SEC: m.http_reqs ? m.http_reqs.values.rate.toFixed(1) : '없음',
    };
    const stats = [['AVG', 'avg'], ['MED', 'med'], ['P95', 'p(95)'], ['MAX', 'max']];
    for (const [key, stat] of stats) {
        values[`K6_DURATION_${key}_MS`] = all ? ms(all[stat]) : '없음';
        values[`K6_CREATED_${key}_MS`] = created ? ms(created[stat]) : '없음';
        values[`K6_CONFLICTED_${key}_MS`] = conflictedValues ? ms(conflictedValues[stat]) : '없음';
    }
    BASE_URLS.forEach((url, i) => {
        values[`K6_SERVER_${i + 1}_URL`] = url;
        values[`K6_SERVER_${i + 1}_REQUESTS`] = count(`requests_server_${i + 1}`);
    });

    const thresholds = [];
    for (const [name, metric] of Object.entries(m)) {
        for (const [expr, result] of Object.entries(metric.thresholds || {})) {
            thresholds.push({ name, expr, ok: result.ok });
        }
    }
    values.K6_THRESHOLDS_OK = thresholds.every((t) => t.ok);

    const pct = (part, whole) => (whole ? ((part / whole) * 100).toFixed(2) : '0.00');
    const line = (label, v) => (v ? `${label} avg ${ms(v.avg)} / med ${ms(v.med)} / p95 ${ms(v['p(95)'])} / max ${ms(v.max)} ms` : `${label} 없음`);
    const text = [
        '',
        `요청 ${requests}건 (인기 구간 ${popular}건, ${pct(popular, requests)}%)`,
        `  201 ${values.K6_STATUS_201} / 409 ${values.K6_STATUS_409} / 예상 밖 HTTP 응답 ${values.K6_UNEXPECTED_STATUS} / 전송 오류 ${values.K6_TRANSPORT_ERRORS}`,
        `  기본 http_req_failed ${m.http_req_failed ? (m.http_req_failed.values.rate * 100).toFixed(2) : '없음'}% (409 포함), 예상 밖 응답 비율 ${pct(values.K6_UNEXPECTED_STATUS + values.K6_TRANSPORT_ERRORS, requests)}%`,
        `완료한 반복 ${values.K6_ITERATIONS_COMPLETE}회, 시작하지 못한 반복 ${values.K6_DROPPED_ITERATIONS}회`,
        `처리량 ${values.K6_REQ_PER_SEC} req/s (시험 시간 ${values.K6_TEST_DURATION_MS} ms)`,
        line('응답 시간 전체', all),
        line('응답 시간 201 ', created),
        line('응답 시간 409 ', conflictedValues),
        ...BASE_URLS.map((url, i) => `서버 ${i + 1} (${url}) 요청 ${values[`K6_SERVER_${i + 1}_REQUESTS`]}건`),
        `check 성공 ${checks.passes}건, 실패 ${checks.fails}건`,
        'threshold',
        ...thresholds.map((t) => `  ${t.ok ? '통과' : '실패'}  ${t.name} ${t.expr}`),
        '',
    ].join('\n');

    const out = { stdout: text };
    if (__ENV.SUMMARY_DIR) {
        out[`${__ENV.SUMMARY_DIR}/k6-summary.json`] = JSON.stringify(data, null, 2);
        out[`${__ENV.SUMMARY_DIR}/k6-metrics.env`] = Object.entries(values).map(([k, v]) => `${k}=${v}`).join('\n') + '\n';
    }
    return out;
}
