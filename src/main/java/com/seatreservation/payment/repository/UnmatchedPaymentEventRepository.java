package com.seatreservation.payment.repository;

import com.seatreservation.payment.domain.UnmatchedPaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface UnmatchedPaymentEventRepository extends JpaRepository<UnmatchedPaymentEvent, String> {

    // 같은 이벤트가 재전송되면 PK가 겹친다. 이때 오류를 내지 않고 처음 기록을 그대로 둔다.
    @Modifying
    @Query(value = """
            INSERT IGNORE INTO unmatched_payment_event
                   (event_id, payment_id, hold_id, status, occurred_at, reason, received_at)
            VALUES (:eventId, :paymentId, :holdId, :status, :occurredAt, :reason, :receivedAt)
            """, nativeQuery = true)
    int insertIgnore(@Param("eventId") String eventId,
                     @Param("paymentId") String paymentId,
                     @Param("holdId") String holdId,
                     @Param("status") String status,
                     @Param("occurredAt") LocalDateTime occurredAt,
                     @Param("reason") String reason,
                     @Param("receivedAt") LocalDateTime receivedAt);
}
