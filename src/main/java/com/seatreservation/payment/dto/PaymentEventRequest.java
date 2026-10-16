package com.seatreservation.payment.dto;

import com.seatreservation.payment.domain.PaymentEventStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public record PaymentEventRequest(
        @NotBlank @Size(max = 64) String eventId,
        @NotBlank @Size(max = 64) String paymentId,
        @NotBlank @Size(max = 36) String holdId,
        @NotNull PaymentEventStatus status,
        @NotNull Instant occurredAt
) {

    public LocalDateTime occurredAtUtc() {
        return LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
    }
}
