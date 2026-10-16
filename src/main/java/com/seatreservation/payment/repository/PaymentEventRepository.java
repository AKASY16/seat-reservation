package com.seatreservation.payment.repository;

import com.seatreservation.payment.domain.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, String> {
}
