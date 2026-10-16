package com.seatreservation.hold.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record HoldRequest(
        @NotNull Long performanceId,
        @NotEmpty List<@NotNull Long> seatIds
) {
}
