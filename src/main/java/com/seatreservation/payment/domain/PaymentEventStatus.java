package com.seatreservation.payment.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PaymentEventStatus {
    APPROVED("approved"),
    CANCELLED("cancelled");

    private final String value;

    PaymentEventStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PaymentEventStatus from(String value) {
        for (PaymentEventStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("지원하지 않는 결제 상태입니다: " + value);
    }
}
