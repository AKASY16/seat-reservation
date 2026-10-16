// 좌석 선점 부하 시험
//
// 조건
//   - 공연 1개, 좌석 1,000석 (V2 마이그레이션의 샘플 공연)
//   - 동시 요청 200 (VU 200), 총 5,000건
//   - 요청마다 좌석 1~4개
//   - 요청의 60%는 앞 두 열(A, B열, 좌석 id 1~100)을 노린다
//
// 실행: README.md의 "부하 시험" 참고

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URLS = (__ENV.BASE_URLS || 'http://localhost:8080').split(',');
const PERFORMANCE_ID = 1;
const POPULAR_SEATS = 100;
const TOTAL_SEATS = 1000;
const POPULAR_RATIO = 0.6;

const held = new Counter('holds_created');
const conflicted = new Counter('holds_conflicted');
const unexpected = new Counter('holds_unexpected_status');

export const options = {
    scenarios: {
        holds: {
            executor: 'shared-iterations',
            vus: 200,
            iterations: 5000,
            maxDuration: '5m',
        },
    },
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
    const baseUrl = BASE_URLS[(__VU + __ITER) % BASE_URLS.length];

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

    if (res.status === 201) {
        held.add(1);
    } else if (res.status === 409) {
        conflicted.add(1);
    } else {
        unexpected.add(1);
    }

    check(res, {
        '201 또는 409': (r) => r.status === 201 || r.status === 409,
    });
}
