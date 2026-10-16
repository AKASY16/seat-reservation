package com.seatreservation.payment.service;

import com.seatreservation.payment.dto.PaymentEventRequest;
import com.seatreservation.payment.repository.UnmatchedPaymentEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 422로 거절한 결제 이벤트를 남긴다. 재전송해도 결과가 같은 이벤트라서,
 승인 이벤트였다면 돈만 받고 판매로 이어지지 않은 결제를 운영자가 찾을 수 있게 한다.

 PaymentEventService 안에서 기록하면 거절 예외로 롤백될 때 기록도 함께 사라진다.
 그래서 처리 트랜잭션이 끝난 뒤 컨트롤러에서 부른다.
 **/
@Service
public class UnmatchedPaymentEventRecorder {

    private final UnmatchedPaymentEventRepository unmatchedPaymentEventRepository;
    private final Clock clock;

    public UnmatchedPaymentEventRecorder(UnmatchedPaymentEventRepository unmatchedPaymentEventRepository, Clock clock) {
        this.unmatchedPaymentEventRepository = unmatchedPaymentEventRepository;
        this.clock = clock;
    }

    @Transactional
    public void record(PaymentEventRequest request, String reason) {
        unmatchedPaymentEventRepository.insertIgnore(request.eventId(), request.paymentId(), request.holdId(),
                request.status().name(), request.occurredAtUtc(), reason, LocalDateTime.now(clock));
    }
}
