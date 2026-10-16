package com.seatreservation.seat.dto;

import java.util.List;

public record PerformanceSeatsResponse(Long performanceId, List<SeatResponse> seats) {
}
