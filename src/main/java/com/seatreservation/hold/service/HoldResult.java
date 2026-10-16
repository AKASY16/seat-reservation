package com.seatreservation.hold.service;

import com.seatreservation.hold.dto.HoldResponse;

// created가 false면 같은 Idempotency-Key로 이미 만들어진 선점을 그대로 돌려준 경우다.
public record HoldResult(HoldResponse hold, boolean created) {
}
