package com.seatreservation.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

@Entity
public class Payment implements Persistable<String> {

    // id를 직접 넣는 엔티티라 save()가 기본으로 merge(SELECT 후 INSERT 또는 UPDATE)를 한다.
    // 새 엔티티는 항상 INSERT하도록 해서, 같은 id가 이미 있으면 덮어쓰지 않고 PK 위반으로 실패하게 한다.
    @Transient
    private boolean isNew = true;

    @Id
    private String paymentId;

    @Column(nullable = false)
    private String holdId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private LocalDateTime approvedAt;

    private LocalDateTime cancelledAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected Payment() {
    }

    public static Payment approved(String paymentId, String holdId, LocalDateTime approvedAt, LocalDateTime now) {
        Payment payment = create(paymentId, holdId, PaymentStatus.APPROVED, now);
        payment.approvedAt = approvedAt;
        return payment;
    }

    public static Payment refundRequired(String paymentId, String holdId, LocalDateTime approvedAt, LocalDateTime now) {
        Payment payment = create(paymentId, holdId, PaymentStatus.REFUND_REQUIRED, now);
        payment.approvedAt = approvedAt;
        return payment;
    }

    public static Payment cancelled(String paymentId, String holdId, LocalDateTime cancelledAt, LocalDateTime now) {
        Payment payment = create(paymentId, holdId, PaymentStatus.CANCELLED, now);
        payment.cancelledAt = cancelledAt;
        return payment;
    }

    private static Payment create(String paymentId, String holdId, PaymentStatus status, LocalDateTime now) {
        Payment payment = new Payment();
        payment.paymentId = paymentId;
        payment.holdId = holdId;
        payment.status = status;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    public void cancel(LocalDateTime cancelledAt, LocalDateTime now) {
        this.status = PaymentStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
        this.updatedAt = now;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getHoldId() {
        return holdId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    @Override
    public String getId() {
        return paymentId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
