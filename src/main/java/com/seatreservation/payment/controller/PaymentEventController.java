package com.seatreservation.payment.controller;

import com.seatreservation.common.BusinessException;
import com.seatreservation.payment.dto.PaymentEventRequest;
import com.seatreservation.payment.dto.PaymentEventResponse;
import com.seatreservation.payment.service.PaymentEventService;
import com.seatreservation.payment.service.UnmatchedPaymentEventRecorder;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentEventController {

    private final PaymentEventService paymentEventService;
    private final UnmatchedPaymentEventRecorder unmatchedPaymentEventRecorder;

    public PaymentEventController(PaymentEventService paymentEventService,
                                  UnmatchedPaymentEventRecorder unmatchedPaymentEventRecorder) {
        this.paymentEventService = paymentEventService;
        this.unmatchedPaymentEventRecorder = unmatchedPaymentEventRecorder;
    }

    // 중복이거나 무시한 이벤트도 200으로 답한다. 실패 응답을 받은 제공자는 같은 이벤트를 계속 다시 보내기 때문이다.
    @PostMapping("/payment-events")
    public PaymentEventResponse receive(@Valid @RequestBody PaymentEventRequest request) {
        try {
            return handleRetryingOnConflict(request);
        } catch (BusinessException e) {
            // 선점을 찾지 못했거나 다른 선점에 연결된 결제라서 거절한 이벤트다.
            // handle()의 트랜잭션이 롤백된 뒤에 기록하므로, 처리용과 기록용 커넥션을 동시에 잡지 않는다.
            unmatchedPaymentEventRecorder.record(request, e.getCode());
            throw e;
        }
    }

    /**
     같은 결제나 같은 이벤트가 서로 다른 선점으로 동시에 들어오면, 선점 행 잠금으로는 줄이 서지 않는다.
     둘 다 처음 보는 결제로 판단해 저장하다가 늦은 쪽이 PK 제약에 걸려 롤백된다.
     그 시점에는 먼저 들어온 쪽이 커밋되어 있으므로, 한 번 더 처리하면 이미 처리한 이벤트(DUPLICATE)나
     다른 선점에 연결된 결제(422, 거절 기록)로 정상 판정된다.
     **/
    private PaymentEventResponse handleRetryingOnConflict(PaymentEventRequest request) {
        try {
            return paymentEventService.handle(request);
        } catch (DataIntegrityViolationException e) {
            return paymentEventService.handle(request);
        }
    }
}
