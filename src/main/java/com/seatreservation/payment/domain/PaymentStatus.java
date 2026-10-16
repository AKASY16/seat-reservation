package com.seatreservation.payment.domain;

public enum PaymentStatus {
    APPROVED,        // 승인되어 좌석 판매 확정
    REFUND_REQUIRED, // 승인됐지만 좌석을 줄 수 없음. 운영자가 환불해야 한다
    CANCELLED        // 결제 취소. 이후 도착하는 승인 이벤트는 무시한다
}
