package com.seatreservation.hold.domain;

public enum SeatHoldStatus {
    ACTIVE,     // 선점 중. 결제를 기다린다
    CONFIRMED,  // 결제 승인으로 판매 확정
    CANCELLED,  // 사용자가 취소했거나, 확정 후 결제가 취소됨
    EXPIRED     // 결제 없이 만료 시각이 지남
}
