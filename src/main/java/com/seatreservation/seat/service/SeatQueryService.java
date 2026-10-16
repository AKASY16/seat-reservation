package com.seatreservation.seat.service;

import com.seatreservation.common.BusinessException;
import com.seatreservation.performance.repository.PerformanceRepository;
import com.seatreservation.seat.dto.PerformanceSeatsResponse;
import com.seatreservation.seat.dto.SeatResponse;
import com.seatreservation.seat.repository.SeatRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class SeatQueryService {

    private final SeatRepository seatRepository;
    private final PerformanceRepository performanceRepository;
    private final Clock clock;

    public SeatQueryService(SeatRepository seatRepository, PerformanceRepository performanceRepository, Clock clock) {
        this.seatRepository = seatRepository;
        this.performanceRepository = performanceRepository;
        this.clock = clock;
    }

    public PerformanceSeatsResponse getSeats(Long performanceId) {
        if (!performanceRepository.existsById(performanceId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PERFORMANCE_NOT_FOUND", "공연을 찾을 수 없습니다.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        List<SeatResponse> seats = seatRepository.findByPerformanceIdOrderByIdAsc(performanceId).stream()
                .map(seat -> new SeatResponse(seat.getId(), seat.getSeatNo(), seat.statusAt(now)))
                .toList();
        return new PerformanceSeatsResponse(performanceId, seats);
    }
}
