package com.seatreservation.payment.service;

import com.seatreservation.common.BusinessException;
import com.seatreservation.hold.dto.HoldRequest;
import com.seatreservation.hold.dto.HoldResponse;
import com.seatreservation.hold.service.HoldResult;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    @DisplayName("한 선점에 서로 다른 결제의 승인이 동시에 와도 하나만 판매되고 나머지는 환불 대상이 된다")
    void concurrentApprovalsOfDifferentPaymentsSellOnce() throws Exception {
        for (int i = 0; i < 10; i++) {
            List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
            HoldResponse hold = hold("user-" + i, seatIds);
            String first = "pay-first-" + i;
            String second = "pay-second-" + i;

            List<Callable<?>> tasks = List.of(
                    () -> paymentEventService.handle(event(first, hold.holdId(), APPROVED, BASE_TIME.plusSeconds(10))),
                    () -> paymentEventService.handle(event(second, hold.holdId(), APPROVED, BASE_TIME.plusSeconds(10)))
            );
            List<Object> results = Concurrently.run(tasks);

            assertThat(results).allMatch(PaymentEventResponse.class::isInstance);
            assertThat(results).extracting(r -> ((PaymentEventResponse) r).result())
                    .containsExactlyInAnyOrder(PaymentEventResult.SOLD, PaymentEventResult.REFUND_REQUIRED);
            assertThat(List.of(fixtures.paymentStatus(first), fixtures.paymentStatus(second)))
                    .containsExactlyInAnyOrder("APPROVED", "REFUND_REQUIRED");
            assertThat(fixtures.seatStatus(seatIds.getFirst())).isEqualTo("SOLD");
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

            // 만료 처리가 먼저 잠그면 true, 승인이 먼저 판매를 확정하면 false다. 어느 쪽이든 예외는 없어야 한다.
            assertThat(results.get(0)).isInstanceOf(Boolean.class);
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

            // 늦은 승인은 선점을 바꾸지 않으므로, 순서와 상관없이 만료 처리는 항상 성공한다.
            assertThat(results.get(0)).isEqualTo(true);
            assertThat(results.get(1)).isInstanceOf(PaymentEventResponse.class);
            assertThat(((PaymentEventResponse) results.get(1)).result()).isEqualTo(PaymentEventResult.REFUND_REQUIRED);
            assertThat(fixtures.paymentStatus(paymentId)).isEqualTo("REFUND_REQUIRED");
            assertThat(fixtures.seatStatus(seatIds.getFirst())).isEqualTo("AVAILABLE");
            assertThat(fixtures.holdStatus(hold.holdId())).isEqualTo("EXPIRED");
        }
    }

    @Test
    @DisplayName("만료 처리, 만료된 좌석을 노리는 새 선점, 이전 선점의 늦은 승인이 동시에 와도 좌석은 한쪽에만 간다")
    void expiryTakeoverAndLateApprovalAtOnce() throws Exception {
        for (int i = 0; i < 10; i++) {
            clock.set(BASE_TIME);
            List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
            Long seatId = seatIds.getFirst();
            long performanceId = fixtures.performanceIdOf(seatId);
            HoldResponse old = hold("old-" + i, seatIds);
            Instant approvedAt = old.expiresAt().minusSeconds(1);
            clock.set(old.expiresAt().plusSeconds(1));
            String newUser = "new-" + i;
            String paymentId = "pay-three-way-" + i;
            // 그냥 동시에 시작하면 거의 항상 새 선점이 먼저 좌석을 잡는다.
            // 짝수 회차에는 새 선점을 조금 늦게 시작해 승인이 먼저 좌석을 잡는 순서도 나오게 한다.
            long takeoverDelayMillis = i % 2 == 0 ? 50 : 0;

            List<Callable<?>> tasks = List.of(
                    () -> seatHoldService.expire(old.holdId()),
                    () -> {
                        Thread.sleep(takeoverDelayMillis);
                        return seatHoldService.hold(newUser, null, new HoldRequest(performanceId, seatIds));
                    },
                    () -> paymentEventService.handle(event(paymentId, old.holdId(), APPROVED, approvedAt))
            );
            List<Object> results = Concurrently.run(tasks);

            assertThat(results.get(0)).isInstanceOf(Boolean.class);
            assertThat(results.get(2)).isInstanceOf(PaymentEventResponse.class);
            if (((PaymentEventResponse) results.get(2)).result() == PaymentEventResult.SOLD) {
                // 승인이 먼저 좌석을 잠갔다. 새 선점은 팔린 좌석을 보고 실패한다.
                assertThat(results.get(1)).isInstanceOf(BusinessException.class);
                assertThat(((BusinessException) results.get(1)).getCode()).isEqualTo("SEAT_UNAVAILABLE");
                assertThat(fixtures.seat(seatId))
                        .containsEntry("status", "SOLD")
                        .containsEntry("hold_id", old.holdId());
                assertThat(fixtures.paymentStatus(paymentId)).isEqualTo("APPROVED");
            } else {
                // 새 선점이 먼저 좌석을 가져갔다. 이전 선점의 결제는 환불 대상이 된다.
                assertThat(((PaymentEventResponse) results.get(2)).result()).isEqualTo(PaymentEventResult.REFUND_REQUIRED);
                assertThat(results.get(1)).isInstanceOf(HoldResult.class);
                assertThat(fixtures.seat(seatId))
                        .containsEntry("status", "HELD")
                        .containsEntry("hold_id", ((HoldResult) results.get(1)).hold().holdId());
                assertThat(fixtures.paymentStatus(paymentId)).isEqualTo("REFUND_REQUIRED");
            }
        }
    }

    @Test
    @DisplayName("같은 좌석을 차례로 잡았다가 만료된 두 선점에 늦은 승인이 동시에 와도 좌석은 한 결제에만 팔린다")
    void lateApprovalsOfTwoExpiredHoldsForSameSeat() throws Exception {
        for (int i = 0; i < 10; i++) {
            clock.set(BASE_TIME);
            List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
            HoldResponse first = hold("first-" + i, seatIds);
            clock.set(first.expiresAt().plusSeconds(1));
            HoldResponse second = hold("second-" + i, seatIds);
            clock.set(second.expiresAt().plusSeconds(1));
            String firstPayment = "pay-late-first-" + i;
            String secondPayment = "pay-late-second-" + i;

            // 두 승인 모두 각자의 선점이 만료되기 전에 일어났다.
            List<Callable<?>> tasks = List.of(
                    () -> paymentEventService.handle(
                            event(firstPayment, first.holdId(), APPROVED, first.expiresAt().minusSeconds(1))),
                    () -> paymentEventService.handle(
                            event(secondPayment, second.holdId(), APPROVED, second.expiresAt().minusSeconds(1)))
            );
            List<Object> results = Concurrently.run(tasks);

            assertThat(results).allMatch(PaymentEventResponse.class::isInstance);
            assertThat(results).extracting(r -> ((PaymentEventResponse) r).result())
                    .containsExactlyInAnyOrder(PaymentEventResult.SOLD, PaymentEventResult.REFUND_REQUIRED);
            String winner = ((PaymentEventResponse) results.get(0)).result() == PaymentEventResult.SOLD
                    ? first.holdId() : second.holdId();
            assertThat(fixtures.seat(seatIds.getFirst()))
                    .containsEntry("status", "SOLD")
                    .containsEntry("hold_id", winner);
        }
    }

    @Test
    @DisplayName("사용자의 선점 취소와 결제 승인이 동시에 와도 판매 확정과 선점 취소 중 하나로만 끝난다")
    void concurrentCancelAndApprovalEndInOneOutcome() throws Exception {
        for (int i = 0; i < 10; i++) {
            List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
            String userId = "user-" + i;
            HoldResponse hold = hold(userId, seatIds);
            String paymentId = "pay-cancel-race-" + i;

            List<Callable<?>> tasks = List.of(
                    () -> {
                        seatHoldService.cancel(hold.holdId(), userId);
                        return true;
                    },
                    () -> paymentEventService.handle(event(paymentId, hold.holdId(), APPROVED, BASE_TIME.plusSeconds(10)))
            );
            List<Object> results = Concurrently.run(tasks);

            assertThat(results.get(1)).isInstanceOf(PaymentEventResponse.class);
            if (((PaymentEventResponse) results.get(1)).result() == PaymentEventResult.SOLD) {
                // 승인이 먼저 잠갔다. 판매가 확정된 선점은 취소할 수 없다.
                assertThat(results.get(0)).isInstanceOf(BusinessException.class);
                assertThat(((BusinessException) results.get(0)).getCode()).isEqualTo("HOLD_CONFIRMED");
                assertThat(fixtures.seatStatus(seatIds.getFirst())).isEqualTo("SOLD");
                assertThat(fixtures.holdStatus(hold.holdId())).isEqualTo("CONFIRMED");
            } else {
                // 취소가 먼저 잠갔다. 승인은 좌석을 받지 못하고 환불 대상이 된다.
                assertThat(((PaymentEventResponse) results.get(1)).result()).isEqualTo(PaymentEventResult.REFUND_REQUIRED);
                assertThat(results.get(0)).isEqualTo(true);
                assertThat(fixtures.seatStatus(seatIds.getFirst())).isEqualTo("AVAILABLE");
                assertThat(fixtures.holdStatus(hold.holdId())).isEqualTo("CANCELLED");
            }
        }
    }

    @Test
    @DisplayName("응답으로 알려 준 만료 시각과 저장된 만료 시각이 같고, 정확히 그 시각에 일어난 승인은 늦은 것으로 본다")
    void approvalAtAdvertisedExpiryIsLate() {
        // 나노초까지 있는 시각에 선점한다. DB는 마이크로초까지만 저장한다.
        clock.set(BASE_TIME.plusNanos(123_456_789));
        List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
        HoldResponse hold = hold("user", seatIds);

        LocalDateTime stored = fixtures.queryForObject("SELECT expires_at FROM seat_hold WHERE id = ?",
                LocalDateTime.class, hold.holdId());
        assertThat(stored.toInstant(ZoneOffset.UTC)).isEqualTo(hold.expiresAt());

        clock.set(hold.expiresAt().plusSeconds(1));
        PaymentEventResponse response = paymentEventService.handle(
                event("pay-boundary", hold.holdId(), APPROVED, hold.expiresAt()));

        assertThat(response.result()).isEqualTo(PaymentEventResult.REFUND_REQUIRED);
        assertThat(fixtures.seatStatus(seatIds.getFirst())).isNotEqualTo("SOLD");
    }

    @Test
    @DisplayName("대소문자나 끝 공백만 다른 eventId는 다른 이벤트로 처리한다")
    void lookAlikeEventIdsAreDifferentEvents() {
        // 처음 eventId와, 그것과 대소문자나 끝 공백만 다른 eventId
        List<List<String>> pairs = List.of(List.of("evt-case", "EVT-CASE"), List.of("evt-space", "evt-space "));
        for (List<String> pair : pairs) {
            List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
            HoldResponse hold = hold("user", seatIds);
            String paymentId = "pay-" + pair.getFirst();
            paymentEventService.handle(new PaymentEventRequest(
                    pair.get(0), paymentId, hold.holdId(), APPROVED, BASE_TIME.plusSeconds(10)));

            PaymentEventResponse cancelled = paymentEventService.handle(new PaymentEventRequest(
                    pair.get(1), paymentId, hold.holdId(), CANCELLED, BASE_TIME.plusSeconds(20)));

            assertThat(cancelled.result()).isEqualTo(PaymentEventResult.SALE_CANCELLED);
            assertThat(fixtures.seatStatus(seatIds.getFirst())).isEqualTo("AVAILABLE");
        }
    }

    @Test
    @DisplayName("대소문자나 끝 공백만 다른 paymentId는 다른 결제로 처리한다")
    void lookAlikePaymentIdsAreDifferentPayments() {
        List<List<String>> pairs = List.of(List.of("pay-upper", "PAY-UPPER"), List.of("pay-space", "pay-space "));
        for (List<String> pair : pairs) {
            List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
            HoldResponse hold = hold("user", seatIds);
            paymentEventService.handle(new PaymentEventRequest(
                    "evt-first-" + pair.get(0), pair.get(0), hold.holdId(), APPROVED, BASE_TIME.plusSeconds(10)));

            PaymentEventResponse second = paymentEventService.handle(new PaymentEventRequest(
                    "evt-second-" + pair.get(0), pair.get(1), hold.holdId(), APPROVED, BASE_TIME.plusSeconds(20)));

            assertThat(second.result()).isEqualTo(PaymentEventResult.REFUND_REQUIRED);
            assertThat(fixtures.paymentStatus(pair.get(0))).isEqualTo("APPROVED");
            assertThat(fixtures.paymentStatus(pair.get(1))).isEqualTo("REFUND_REQUIRED");
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
