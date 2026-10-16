package com.seatreservation.payment.dto;

import com.seatreservation.payment.domain.PaymentEventStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public record PaymentEventRequest(
        @NotBlank @Size(max = 64) String eventId,
        @NotBlank @Size(max = 64) String paymentId,
        @NotBlank @Size(max = 36) String holdId,
        @NotNull PaymentEventStatus status,
        @NotNull Instant occurredAt
) {

    // 만료 시각과 같은 정밀도(마이크로초)로 맞춘다. DB에 저장되는 값과 비교에 쓰는 값이 같아진다.
    public LocalDateTime occurredAtUtc() {
        return LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
