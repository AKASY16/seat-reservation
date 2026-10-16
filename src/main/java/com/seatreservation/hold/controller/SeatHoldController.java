package com.seatreservation.hold.controller;

import com.seatreservation.hold.dto.HoldRequest;
import com.seatreservation.hold.dto.HoldResponse;
import com.seatreservation.hold.service.HoldResult;
import com.seatreservation.hold.service.SeatHoldService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
    // 길이는 DB 컬럼(64자)에 맞춰 검사한다. 넘는 값이 DB까지 가면 저장 단계에서 실패해 500이 된다.
    static final String USER_ID_HEADER = "X-User-Id";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final SeatHoldService seatHoldService;

    public SeatHoldController(SeatHoldService seatHoldService) {
        this.seatHoldService = seatHoldService;
    }

    @PostMapping("/holds")
    public ResponseEntity<HoldResponse> hold(@RequestHeader(USER_ID_HEADER) @NotBlank @Size(max = 64) String userId,
                                             @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
                                             @Pattern(regexp = "\\S{1,64}") String idempotencyKey,
                                             @Valid @RequestBody HoldRequest request) {
        HoldResult result = seatHoldService.hold(userId, idempotencyKey, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.hold());
    }

    @DeleteMapping("/holds/{holdId}")
    public ResponseEntity<Void> cancel(@RequestHeader(USER_ID_HEADER) @NotBlank @Size(max = 64) String userId,
                                       @PathVariable String holdId) {
        seatHoldService.cancel(holdId, userId);
        return ResponseEntity.noContent().build();
    }
}
