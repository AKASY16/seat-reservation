package com.seatreservation.payment.controller;

import com.seatreservation.payment.dto.PaymentEventRequest;
import com.seatreservation.payment.dto.PaymentEventResponse;
import com.seatreservation.payment.service.PaymentEventService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentEventController {

    private final PaymentEventService paymentEventService;

    public PaymentEventController(PaymentEventService paymentEventService) {
        this.paymentEventService = paymentEventService;
    }

    // 중복이거나 무시한 이벤트도 200으로 답한다. 실패 응답을 받은 제공자는 같은 이벤트를 계속 다시 보내기 때문이다.
    @PostMapping("/payment-events")
    public PaymentEventResponse receive(@Valid @RequestBody PaymentEventRequest request) {
        return paymentEventService.handle(request);
    }
}
