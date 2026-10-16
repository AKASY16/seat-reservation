package com.seatreservation.hold.dto;

import com.seatreservation.hold.domain.SeatHold;
import com.seatreservation.hold.domain.SeatHoldStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

public record HoldResponse(
        String holdId,
        Long performanceId,
        List<Long> seatIds,
        SeatHoldStatus status,
        Instant expiresAt
) {

    public static HoldResponse from(SeatHold hold) {
        return new HoldResponse(
                hold.getId(),
                hold.getPerformanceId(),
                List.copyOf(hold.getSeatIds()),
                hold.getStatus(),
                hold.getExpiresAt().toInstant(ZoneOffset.UTC)
        );
    }
}
