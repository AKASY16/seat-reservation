package com.seatreservation.payment.service;

import com.seatreservation.hold.dto.HoldRequest;
import com.seatreservation.hold.dto.HoldResponse;
import com.seatreservation.hold.service.SeatHoldService;
import com.seatreservation.payment.domain.PaymentEventResult;
import com.seatreservation.payment.domain.PaymentEventStatus;
import com.seatreservation.payment.dto.PaymentEventRequest;
import com.seatreservation.payment.dto.PaymentEventResponse;
import com.seatreservation.support.Concurrently;
import com.seatreservation.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static com.seatreservation.payment.domain.PaymentEventStatus.APPROVED;
import static com.seatreservation.payment.domain.PaymentEventStatus.CANCELLED;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentEventServiceTest extends IntegrationTestSupport {

    @Autowired
    SeatHoldService seatHoldService;

    @Autowired
    PaymentEventService paymentEventService;

    @Test
    @DisplayName("결제 승인 이벤트를 받으면 선점한 좌석이 판매 확정된다")
    void approvedEventSellsSeats() {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(2);
        HoldResponse hold = hold("user", seatIds);

        PaymentEventResponse response = paymentEventService.handle(
                event("pay-1", hold.holdId(), APPROVED, BASE_TIME.plusSeconds(10)));

        assertThat(response.result()).isEqualTo(PaymentEventResult.SOLD);
        assertThat(seatIds).allSatisfy(id -> assertThat(fixtures.seatStatus(id)).isEqualTo("SOLD"));
        assertThat(fixtures.holdStatus(hold.holdId())).isEqualTo("CONFIRMED");
        assertThat(fixtures.paymentStatus("pay-1")).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("같은 결제 이벤트를 동시에 10번 재전송해도 한 번만 반영된다")
    void concurrentDuplicateEventsAreAppliedOnce() throws Exception {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(2);
        HoldResponse hold = hold("user", seatIds);
        PaymentEventRequest event = event("pay-dup", hold.holdId(), APPROVED, BASE_TIME.plusSeconds(10));

        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> paymentEventService.handle(event));
        }
        List<Object> results = Concurrently.run(tasks);

        assertThat(results).allMatch(PaymentEventResponse.class::isInstance);
        assertThat(results).extracting(r -> ((PaymentEventResponse) r).result())
                .containsOnlyOnce(PaymentEventResult.SOLD)
                .filteredOn(r -> r != PaymentEventResult.SOLD)
                .containsOnly(PaymentEventResult.DUPLICATE);
        assertThat(fixtures.count("SELECT COUNT(*) FROM payment_event WHERE event_id = ?", event.eventId()))
                .isEqualTo(1);
        assertThat(fixtures.count("SELECT COUNT(*) FROM payment WHERE hold_id = ?", hold.holdId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("결제 취소가 승인보다 먼저 도착하면 승인은 무시되고 좌석은 판매되지 않는다")
    void cancelledBeforeApprovedKeepsPaymentCancelled() {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
        HoldResponse hold = hold("user", seatIds);

        PaymentEventResponse cancelled = paymentEventService.handle(
                event("pay-2", hold.holdId(), CANCELLED, BASE_TIME.plusSeconds(20)));
        PaymentEventResponse approved = paymentEventService.handle(
                event("pay-2", hold.holdId(), APPROVED, BASE_TIME.plusSeconds(10)));

        assertThat(cancelled.result()).isEqualTo(PaymentEventResult.PAYMENT_CANCELLED);
        assertThat(approved.result()).isEqualTo(PaymentEventResult.IGNORED);
        assertThat(fixtures.paymentStatus("pay-2")).isEqualTo("CANCELLED");
        assertThat(fixtures.seatStatus(seatIds.getFirst())).isEqualTo("HELD");
        assertThat(fixtures.holdStatus(hold.holdId())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("같은 결제의 승인과 취소가 동시에 도착해도 최종 상태는 항상 취소다")
    void concurrentApprovedAndCancelledEndInCancelled() throws Exception {
        for (int i = 0; i < 10; i++) {
            List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
            HoldResponse hold = hold("user-" + i, seatIds);
            String paymentId = "pay-race-" + i;

            List<Callable<?>> tasks = List.of(
                    () -> paymentEventService.handle(event(paymentId, hold.holdId(), APPROVED, BASE_TIME.plusSeconds(10))),
                    () -> paymentEventService.handle(event(paymentId, hold.holdId(), CANCELLED, BASE_TIME.plusSeconds(20)))
            );
            List<Object> results = Concurrently.run(tasks);

            assertThat(results).allMatch(PaymentEventResponse.class::isInstance);
            assertThat(fixtures.paymentStatus(paymentId)).isEqualTo("CANCELLED");
            assertThat(fixtures.seatStatus(seatIds.getFirst())).isNotEqualTo("SOLD");
        }
    }

    @Test
    @DisplayName("판매 확정 후 결제가 취소되면 좌석을 다시 판매할 수 있다")
    void cancelledAfterApprovedReleasesSeats() {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(2);
        HoldResponse hold = hold("user", seatIds);
        paymentEventService.handle(event("pay-3", hold.holdId(), APPROVED, BASE_TIME.plusSeconds(10)));

        PaymentEventResponse response = paymentEventService.handle(
                event("pay-3", hold.holdId(), CANCELLED, BASE_TIME.plusSeconds(60)));

        assertThat(response.result()).isEqualTo(PaymentEventResult.SALE_CANCELLED);
        assertThat(seatIds).allSatisfy(id -> assertThat(fixtures.seatStatus(id)).isEqualTo("AVAILABLE"));
        assertThat(fixtures.holdStatus(hold.holdId())).isEqualTo("CANCELLED");
        assertThat(hold("next-user", seatIds)).isNotNull();
    }

    @Test
    @DisplayName("만료 전에 일어난 결제 승인은 만료 처리와 동시에 처리되어도 판매 확정된다")
    void approvalBeforeExpiryWinsOverConcurrentExpiry() throws Exception {
        for (int i = 0; i < 10; i++) {
            clock.set(BASE_TIME);
            List<Long> seatIds = fixtures.createPerformanceWithSeats(2);
            HoldResponse hold = hold("user-" + i, seatIds);
            Instant approvedAt = hold.expiresAt().minusSeconds(1);
            clock.set(hold.expiresAt().plusSeconds(1));
            String paymentId = "pay-expiry-" + i;

            List<Callable<?>> tasks = List.of(
                    () -> seatHoldService.expire(hold.holdId()),
                    () -> paymentEventService.handle(event(paymentId, hold.holdId(), APPROVED, approvedAt))
            );
            List<Object> results = Concurrently.run(tasks);

            assertThat(results.get(1)).isInstanceOf(PaymentEventResponse.class);
            assertThat(((PaymentEventResponse) results.get(1)).result()).isEqualTo(PaymentEventResult.SOLD);
            assertThat(seatIds).allSatisfy(id -> assertThat(fixtures.seatStatus(id)).isEqualTo("SOLD"));
            assertThat(fixtures.holdStatus(hold.holdId())).isEqualTo("CONFIRMED");
        }
    }

    @Test
    @DisplayName("만료 시각 이후에 일어난 결제 승인은 만료 처리와 동시에 처리되어도 환불 대상이 되고 좌석은 판매되지 않는다")
    void approvalAfterExpiryLosesToConcurrentExpiry() throws Exception {
        for (int i = 0; i < 10; i++) {
            clock.set(BASE_TIME);
            List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
            HoldResponse hold = hold("user-" + i, seatIds);
            Instant approvedAt = hold.expiresAt().plusSeconds(1);
            clock.set(hold.expiresAt().plusSeconds(2));
            String paymentId = "pay-late-" + i;

            List<Callable<?>> tasks = List.of(
                    () -> seatHoldService.expire(hold.holdId()),
                    () -> paymentEventService.handle(event(paymentId, hold.holdId(), APPROVED, approvedAt))
            );
            List<Object> results = Concurrently.run(tasks);

            assertThat(((PaymentEventResponse) results.get(1)).result()).isEqualTo(PaymentEventResult.REFUND_REQUIRED);
            assertThat(fixtures.paymentStatus(paymentId)).isEqualTo("REFUND_REQUIRED");
            assertThat(fixtures.seatStatus(seatIds.getFirst())).isEqualTo("AVAILABLE");
            assertThat(fixtures.holdStatus(hold.holdId())).isEqualTo("EXPIRED");
        }
    }

    @Test
    @DisplayName("만료 전에 승인됐어도 그사이 다른 사용자가 좌석을 선점했다면 환불 대상이 된다")
    void lateApprovalForReheldSeatRequiresRefund() {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
        HoldResponse first = hold("first", seatIds);
        clock.set(first.expiresAt().plusSeconds(1));
        HoldResponse second = hold("second", seatIds);

        PaymentEventResponse response = paymentEventService.handle(
                event("pay-4", first.holdId(), APPROVED, first.expiresAt().minusSeconds(1)));

        assertThat(response.result()).isEqualTo(PaymentEventResult.REFUND_REQUIRED);
        assertThat(fixtures.seat(seatIds.getFirst()))
                .containsEntry("status", "HELD")
                .containsEntry("hold_id", second.holdId());
    }

    @Test
    @DisplayName("사용자가 취소한 선점에 결제 승인이 도착하면 환불 대상이 된다")
    void approvalForCancelledHoldRequiresRefund() {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
        HoldResponse hold = hold("user", seatIds);
        seatHoldService.cancel(hold.holdId(), "user");

        PaymentEventResponse response = paymentEventService.handle(
                event("pay-5", hold.holdId(), APPROVED, BASE_TIME.plusSeconds(10)));

        assertThat(response.result()).isEqualTo(PaymentEventResult.REFUND_REQUIRED);
        assertThat(fixtures.seatStatus(seatIds.getFirst())).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("이미 판매 확정된 선점에 다른 결제가 승인되면 두 번째 결제는 환불 대상이 된다")
    void secondPaymentForConfirmedHoldRequiresRefund() {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
        HoldResponse hold = hold("user", seatIds);
        paymentEventService.handle(event("pay-6", hold.holdId(), APPROVED, BASE_TIME.plusSeconds(10)));

        PaymentEventResponse response = paymentEventService.handle(
                event("pay-7", hold.holdId(), APPROVED, BASE_TIME.plusSeconds(20)));

        assertThat(response.result()).isEqualTo(PaymentEventResult.REFUND_REQUIRED);
        assertThat(fixtures.paymentStatus("pay-6")).isEqualTo("APPROVED");
        assertThat(fixtures.seatStatus(seatIds.getFirst())).isEqualTo("SOLD");
    }

    private HoldResponse hold(String userId, List<Long> seatIds) {
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());
        return seatHoldService.hold(userId, null, new HoldRequest(performanceId, seatIds)).hold();
    }

    private PaymentEventRequest event(String paymentId, String holdId, PaymentEventStatus status, Instant occurredAt) {
        return new PaymentEventRequest("evt-" + paymentId + "-" + status.getValue(), paymentId, holdId, status, occurredAt);
    }
}
