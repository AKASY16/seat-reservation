package com.seatreservation.seat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

@Entity
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long performanceId;

    @Column(nullable = false)
    private String seatNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    private String holdId; // 현재 이 좌석을 잡고 있는 선점. AVAILABLE이면 null

    private LocalDateTime heldUntil; // HELD일 때 선점 만료 시각

    protected Seat() {
    }

    // 선점 만료 시각이 지난 HELD 좌석은 만료 작업이 아직 정리하지 않았어도 빈 좌석으로 본다.
    public SeatStatus statusAt(LocalDateTime now) {
        if (status == SeatStatus.HELD && !now.isBefore(heldUntil)) {
            return SeatStatus.AVAILABLE;
        }
        return status;
    }

    public boolean isHeldBy(String holdId) {
        return status == SeatStatus.HELD && holdId.equals(this.holdId);
    }

    public void sell(String holdId) {
        this.status = SeatStatus.SOLD;
        this.holdId = holdId;
        this.heldUntil = null;
    }

    public Long getId() {
        return id;
    }

    public Long getPerformanceId() {
        return performanceId;
    }

    public String getSeatNo() {
        return seatNo;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public String getHoldId() {
        return holdId;
    }

    public LocalDateTime getHeldUntil() {
        return heldUntil;
    }
}
