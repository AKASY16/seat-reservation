package com.seatreservation.payment.dto;

import com.seatreservation.payment.domain.PaymentEventResult;

public record PaymentEventResponse(String eventId, PaymentEventResult result) {
}
