package com.seatreservation.payment.service;

import com.seatreservation.common.BusinessException;
import com.seatreservation.hold.domain.SeatHold;
import com.seatreservation.hold.domain.SeatHoldStatus;
import com.seatreservation.hold.repository.SeatHoldRepository;
import com.seatreservation.payment.domain.Payment;
import com.seatreservation.payment.domain.PaymentEvent;
import com.seatreservation.payment.domain.PaymentEventResult;
import com.seatreservation.payment.dto.PaymentEventRequest;
import com.seatreservation.payment.dto.PaymentEventResponse;
import com.seatreservation.payment.repository.PaymentEventRepository;
import com.seatreservation.payment.repository.PaymentRepository;
import com.seatreservation.seat.domain.Seat;
import com.seatreservation.seat.domain.SeatStatus;
import com.seatreservation.seat.repository.SeatRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 결제 제공자가 보낸 이벤트를 반영한다. 규칙은 DECISIONS.md의 결제 이벤트 항목에 정리했다.

 이벤트 하나를 트랜잭션 하나로 처리한다. 처리 도중 프로세스가 죽으면 이벤트 기록까지 함께 롤백되고,
 제공자가 같은 이벤트를 다시 보내면 처음부터 다시 처리된다.
 **/
@Service
@Transactional
public class PaymentEventService {

    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final Clock clock;

    public PaymentEventService(SeatHoldRepository seatHoldRepository,
                               SeatRepository seatRepository,
                               PaymentRepository paymentRepository,
                               PaymentEventRepository paymentEventRepository,
                               Clock clock) {
        this.seatHoldRepository = seatHoldRepository;
        this.seatRepository = seatRepository;
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.clock = clock;
    }

    public PaymentEventResponse handle(PaymentEventRequest request) {
        // 선점 행을 가장 먼저 잠근다. 같은 선점에 대한 결제 이벤트, 만료 처리, 선점 취소가 여기서 한 줄로 선다.
        // 같은 eventId가 동시에 두 번 와도 두 번째는 첫 번째가 커밋할 때까지 기다렸다가 아래 중복 검사에 걸린다.
        SeatHold hold = seatHoldRepository.findByIdForUpdate(request.holdId())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "HOLD_NOT_FOUND",
                        "결제 이벤트의 선점을 찾을 수 없습니다."));

        if (paymentEventRepository.existsById(request.eventId())) {
            return new PaymentEventResponse(request.eventId(), PaymentEventResult.DUPLICATE);
        }

        Optional<Payment> payment = paymentRepository.findById(request.paymentId());
        if (payment.isPresent() && !payment.get().getHoldId().equals(hold.getId())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_HOLD_MISMATCH",
                    "이미 다른 선점에 연결된 결제입니다.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        PaymentEventResult result = switch (request.status()) {
            case APPROVED -> approve(hold, payment, request, now);
            case CANCELLED -> cancel(hold, payment, request, now);
        };

        paymentEventRepository.save(new PaymentEvent(request, result, now));
        return new PaymentEventResponse(request.eventId(), result);
    }

    private PaymentEventResult approve(SeatHold hold, Optional<Payment> existing,
                                       PaymentEventRequest request, LocalDateTime now) {
        // 결제 하나는 한 번만 승인된다. 이미 승인됐거나, 취소가 먼저 도착했거나, 환불 대상이면 더 바꿀 것이 없다.
        if (existing.isPresent()) {
            return PaymentEventResult.IGNORED;
        }

        LocalDateTime approvedAt = request.occurredAtUtc();
        List<Seat> seats = sellableSeats(hold, approvedAt, now);
        if (seats.isEmpty()) {
            paymentRepository.save(Payment.refundRequired(request.paymentId(), hold.getId(), approvedAt, now));
            return PaymentEventResult.REFUND_REQUIRED;
        }

        seats.forEach(seat -> seat.sell(hold.getId()));
        hold.confirm(now);
        paymentRepository.save(Payment.approved(request.paymentId(), hold.getId(), approvedAt, now));
        return PaymentEventResult.SOLD;
    }

    /**
     좌석을 팔 수 있으면 잠근 좌석 목록을, 팔 수 없으면 빈 목록을 돌려준다.

     승인이 선점 만료 시각 전에 일어났다면, 만료 처리가 먼저 끝났더라도 좌석이 아직 비어 있는 한 판매한다.
     처리 순서가 아니라 결제가 실제로 일어난 시각으로 결과를 정하기 위해서다.
     **/
    private List<Seat> sellableSeats(SeatHold hold, LocalDateTime approvedAt, LocalDateTime now) {
        boolean holdOpen = hold.getStatus() == SeatHoldStatus.ACTIVE || hold.getStatus() == SeatHoldStatus.EXPIRED;
        boolean approvedInTime = approvedAt.isBefore(hold.getExpiresAt());
        if (!holdOpen || !approvedInTime) {
            return List.of();
        }

        List<Seat> seats = seatRepository.findAllByIdForUpdate(hold.getSeatIds());
        boolean allAvailable = seats.size() == hold.getSeatIds().size()
                && seats.stream().allMatch(seat ->
                seat.isHeldBy(hold.getId()) || seat.statusAt(now) == SeatStatus.AVAILABLE);
        return allAvailable ? seats : List.of();
    }

    private PaymentEventResult cancel(SeatHold hold, Optional<Payment> existing,
                                      PaymentEventRequest request, LocalDateTime now) {
        LocalDateTime cancelledAt = request.occurredAtUtc();
        if (existing.isEmpty()) {
            // 취소가 승인보다 먼저 도착했다. 취소를 최종 상태로 기록해 두고, 나중에 오는 승인은 무시한다.
            paymentRepository.save(Payment.cancelled(request.paymentId(), hold.getId(), cancelledAt, now));
            return PaymentEventResult.PAYMENT_CANCELLED;
        }

        Payment payment = existing.get();
        return switch (payment.getStatus()) {
            case CANCELLED -> PaymentEventResult.IGNORED;
            case REFUND_REQUIRED -> {
                payment.cancel(cancelledAt, now);
                yield PaymentEventResult.PAYMENT_CANCELLED;
            }
            case APPROVED -> {
                payment.cancel(cancelledAt, now);
                hold.cancel(now);
                seatRepository.releaseSold(hold.getId(), hold.getSeatIds());
                yield PaymentEventResult.SALE_CANCELLED;
            }
        };
    }
}
