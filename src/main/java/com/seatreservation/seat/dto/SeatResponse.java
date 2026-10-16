package com.seatreservation.seat.dto;

import com.seatreservation.seat.domain.SeatStatus;

public record SeatResponse(Long seatId, String seatNo, SeatStatus status) {
}
