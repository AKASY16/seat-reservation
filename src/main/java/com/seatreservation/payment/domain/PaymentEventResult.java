package com.seatreservation.payment.domain;

public enum PaymentEventResult {
    SOLD,              // 승인 반영. 좌석 판매 확정
    REFUND_REQUIRED,   // 승인됐지만 좌석을 줄 수 없어 환불 대상으로 기록
    SALE_CANCELLED,    // 판매 확정된 결제가 취소되어 좌석을 다시 판매 가능으로 되돌림
    PAYMENT_CANCELLED, // 좌석에는 영향 없이 결제만 취소 상태로 기록 (승인 전 취소, 환불 대상 취소)
    IGNORED,           // 이미 최종 상태라 반영할 것이 없음 (취소 뒤 도착한 승인 등)
    DUPLICATE          // 이미 처리한 eventId
}
