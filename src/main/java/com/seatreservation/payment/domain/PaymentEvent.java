package com.seatreservation.payment.domain;

import com.seatreservation.payment.dto.PaymentEventRequest;
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
public class PaymentEvent implements Persistable<String> {

    // id를 직접 넣는 엔티티라 save()가 기본으로 merge(SELECT 후 INSERT 또는 UPDATE)를 한다.
    // 새 엔티티는 항상 INSERT하도록 해서, 같은 id가 이미 있으면 덮어쓰지 않고 PK 위반으로 실패하게 한다.
    @Transient
    private boolean isNew = true;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentEventResult result;

    @Column(nullable = false)
    private LocalDateTime receivedAt;

    protected PaymentEvent() {
    }

    public PaymentEvent(PaymentEventRequest request, PaymentEventResult result, LocalDateTime receivedAt) {
        this.eventId = request.eventId();
        this.paymentId = request.paymentId();
        this.holdId = request.holdId();
        this.status = request.status();
        this.occurredAt = request.occurredAtUtc();
        this.result = result;
        this.receivedAt = receivedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public PaymentEventResult getResult() {
        return result;
    }

    @Override
    public String getId() {
        return eventId;
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
