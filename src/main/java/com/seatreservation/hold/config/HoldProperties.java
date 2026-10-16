package com.seatreservation.hold.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "reservation.hold")
public record HoldProperties(Duration ttl, int maxSeats, Expiry expiry) {

    public record Expiry(Duration interval, int batchSize) {
    }
}
