package com.seatreservation.performance.repository;

import com.seatreservation.performance.domain.Performance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {
}
