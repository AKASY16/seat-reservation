package com.seatreservation.seat.controller;

import com.seatreservation.seat.dto.PerformanceSeatsResponse;
import com.seatreservation.seat.service.SeatQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeatController {

    private final SeatQueryService seatQueryService;

    public SeatController(SeatQueryService seatQueryService) {
        this.seatQueryService = seatQueryService;
    }

    @GetMapping("/performances/{performanceId}/seats")
    public PerformanceSeatsResponse getSeats(@PathVariable Long performanceId) {
        return seatQueryService.getSeats(performanceId);
    }
}
