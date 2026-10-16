package com.seatreservation.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

// 저장은 UnmatchedPaymentEventRepository.insertIgnore로만 한다. 엔티티는 리포지토리와 스키마 검증을 위해 둔다.
@Entity
public class UnmatchedPaymentEvent {

    @Id
    private String eventId;

    @Column(nullable = false)
    private String paymentId;

    @Column(nullable = false)
    private String holdId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentEventStatus status;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime receivedAt;

    protected UnmatchedPaymentEvent() {
    }
}
