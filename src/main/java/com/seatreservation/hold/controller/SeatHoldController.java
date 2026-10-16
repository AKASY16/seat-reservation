package com.seatreservation.hold.controller;

import com.seatreservation.hold.dto.HoldRequest;
import com.seatreservation.hold.dto.HoldResponse;
import com.seatreservation.hold.service.HoldResult;
import com.seatreservation.hold.service.SeatHoldService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeatHoldController {

    // 인증은 이 프로젝트 범위 밖이라 헤더로 받은 사용자 식별자를 그대로 믿는다.
    static final String USER_ID_HEADER = "X-User-Id";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final SeatHoldService seatHoldService;

    public SeatHoldController(SeatHoldService seatHoldService) {
        this.seatHoldService = seatHoldService;
    }

    @PostMapping("/holds")
    public ResponseEntity<HoldResponse> hold(@RequestHeader(USER_ID_HEADER) String userId,
                                             @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
                                             @Valid @RequestBody HoldRequest request) {
        HoldResult result = seatHoldService.hold(userId, idempotencyKey, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.hold());
    }

    @DeleteMapping("/holds/{holdId}")
    public ResponseEntity<Void> cancel(@RequestHeader(USER_ID_HEADER) String userId,
                                       @PathVariable String holdId) {
        seatHoldService.cancel(holdId, userId);
        return ResponseEntity.noContent().build();
    }
}
