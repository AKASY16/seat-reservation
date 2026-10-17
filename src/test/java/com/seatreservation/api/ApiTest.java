package com.seatreservation.api;

import com.seatreservation.support.Concurrently;
import com.seatreservation.support.IntegrationTestSupport;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청·응답 형식과 상태 코드를 HTTP 수준에서 확인한다. 동시성 규칙은 서비스 테스트에서 따로 검증한다.
@AutoConfigureMockMvc
class ApiTest extends IntegrationTestSupport {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("좌석을 선점하고, 조회하고, 결제 승인 후 판매 확정 상태를 확인한다")
    void holdAndPayFlow() throws Exception {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(3);
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());
        String body = holdBody(performanceId, seatIds.get(0), seatIds.get(1));

        MvcResult created = mockMvc.perform(post("/holds")
                        .header("X-User-Id", "user-1")
                        .header("Idempotency-Key", "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seatIds.length()").value(2))
                .andReturn();
        String holdId = JsonPath.read(created.getResponse().getContentAsString(), "$.holdId");

        // 같은 키로 다시 보내면 새로 만들지 않고 같은 선점을 200으로 돌려준다.
        mockMvc.perform(post("/holds")
                        .header("X-User-Id", "user-1")
                        .header("Idempotency-Key", "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId));

        mockMvc.perform(post("/holds")
                        .header("X-User-Id", "user-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(performanceId, seatIds.get(1), seatIds.get(2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));

        mockMvc.perform(get("/performances/{id}/seats", performanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[0].status").value("HELD"))
                .andExpect(jsonPath("$.seats[2].status").value("AVAILABLE"));

        mockMvc.perform(post("/payment-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt-api-1","paymentId":"pay-api-1","holdId":"%s",
                                 "status":"approved","occurredAt":"2026-10-18T03:00:30Z"}
                                """.formatted(holdId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SOLD"));

        mockMvc.perform(get("/performances/{id}/seats", performanceId))
                .andExpect(jsonPath("$.seats[0].status").value("SOLD"))
                .andExpect(jsonPath("$.seats[1].status").value("SOLD"));
    }

    @Test
    @DisplayName("선점을 취소하면 204를 돌려주고, 다른 사용자의 선점은 취소할 수 없다")
    void cancelHold() throws Exception {

        List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());

        MvcResult created = mockMvc.perform(post("/holds")
                        .header("X-User-Id", "owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(performanceId, seatIds.getFirst())))
                .andExpect(status().isCreated())
                .andReturn();

        String holdId = JsonPath.read(created.getResponse().getContentAsString(), "$.holdId");

        mockMvc.perform(delete("/holds/{id}", holdId).header("X-User-Id", "someone-else"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/holds/{id}", holdId).header("X-User-Id", "owner"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/holds/{id}", holdId).header("X-User-Id", "owner"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/performances/{id}/seats", performanceId))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"));
    }

    @Test
    @DisplayName("잘못된 요청은 400으로 거절한다")
    void rejectsInvalidRequests() throws Exception {
        mockMvc.perform(post("/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(1L, 1L)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/holds")
                        .header("X-User-Id", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"performanceId\":1,\"seatIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(startsWith("seatIds")));

        mockMvc.perform(post("/payment-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt-x","paymentId":"pay-x","holdId":"hold-x",
                                 "status":"refunded","occurredAt":"2026-10-18T03:00:30Z"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/performances/{id}/seats", 999_999))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DB 컬럼보다 긴 사용자 식별자나 Idempotency-Key는 400으로 거절한다")
    void rejectsTooLongHeaders() throws Exception {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
        String body = holdBody(fixtures.performanceIdOf(seatIds.getFirst()), seatIds.getFirst());
        String tooLong = "u".repeat(65);

        mockMvc.perform(post("/holds")
                        .header("X-User-Id", tooLong)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/holds")
                        .header("X-User-Id", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/holds")
                        .header("X-User-Id", "user")
                        .header("Idempotency-Key", "k".repeat(65))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(delete("/holds/{id}", "any-hold").header("X-User-Id", tooLong))
                .andExpect(status().isBadRequest());

        assertThat(fixtures.seatStatus(seatIds.getFirst())).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("소수점이 있는 공연·좌석 id는 정수로 바꾸지 않고 400으로 거절한다")
    void rejectsFractionalIds() throws Exception {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
        Long seatId = seatIds.getFirst();
        long performanceId = fixtures.performanceIdOf(seatId);

        // 소수 부분을 버리면 둘 다 실제로 있는 공연과 좌석이 된다.
        List<String> bodies = List.of(
                "{\"performanceId\":%d,\"seatIds\":[%d.9]}".formatted(performanceId, seatId),
                "{\"performanceId\":%d.9,\"seatIds\":[%d]}".formatted(performanceId, seatId));
        for (String body : bodies) {
            mockMvc.perform(post("/holds")
                            .header("X-User-Id", "fraction")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        assertThat(fixtures.seatStatus(seatId)).isEqualTo("AVAILABLE");
        assertThat(fixtures.count("SELECT COUNT(*) FROM seat_hold WHERE user_id = ?", "fraction")).isZero();
    }

    @Test
    @DisplayName("DB에 저장할 수 없는 결제 시각은 400으로 거절하고 아무것도 기록하지 않는다")
    void rejectsUnstorableOccurredAt() throws Exception {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
        String holdId = createHold("payer", fixtures.performanceIdOf(seatIds.getFirst()), seatIds.getFirst());

        for (String occurredAt : List.of("+10000-01-01T00:00:00Z", "0999-12-31T23:59:59Z")) {
            mockMvc.perform(post("/payment-events").contentType(MediaType.APPLICATION_JSON)
                            .content(paymentEvent("evt-range", "pay-range", holdId, "approved", occurredAt)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(startsWith("occurredAt")));
        }

        assertThat(fixtures.count("SELECT COUNT(*) FROM payment_event WHERE event_id = ?", "evt-range")).isZero();
        assertThat(fixtures.count("SELECT COUNT(*) FROM unmatched_payment_event WHERE event_id = ?", "evt-range")).isZero();
        assertThat(fixtures.count("SELECT COUNT(*) FROM payment WHERE payment_id = ?", "pay-range")).isZero();
        assertThat(fixtures.holdStatus(holdId)).isEqualTo("ACTIVE");

        // 범위의 양 끝 값은 저장된다.
        mockMvc.perform(post("/payment-events").contentType(MediaType.APPLICATION_JSON)
                        .content(paymentEvent("evt-range-max", "pay-range", holdId, "approved", "9999-12-31T23:59:59.999999Z")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/payment-events").contentType(MediaType.APPLICATION_JSON)
                        .content(paymentEvent("evt-range-min", "pay-range", holdId, "approved", "1000-01-01T00:00:00Z")))
                .andExpect(status().isOk());
        assertThat(fixtures.count("SELECT COUNT(*) FROM payment_event WHERE event_id IN (?, ?)",
                "evt-range-max", "evt-range-min")).isEqualTo(2);
    }

    @Test
    @DisplayName("처리할 수 없는 결제 이벤트는 422로 거절하고, 재전송되어도 한 번만 보관한다")
    void rejectedPaymentEventsAreStoredOnce() throws Exception {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(2);
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());
        String firstHold = createHold("payer", performanceId, seatIds.get(0));
        String secondHold = createHold("payer", performanceId, seatIds.get(1));
        String unknownHold = paymentEvent("evt-api-unknown", "pay-api-2", "unknown-hold", "approved");

        // 선점을 찾을 수 없는 이벤트를 두 번 보낸다.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/payment-events").contentType(MediaType.APPLICATION_JSON).content(unknownHold))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        }

        // 첫 번째 선점에 연결된 결제가 두 번째 선점으로 다시 온다.
        mockMvc.perform(post("/payment-events").contentType(MediaType.APPLICATION_JSON)
                        .content(paymentEvent("evt-api-3", "pay-api-3", firstHold, "approved")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/payment-events").contentType(MediaType.APPLICATION_JSON)
                        .content(paymentEvent("evt-api-4", "pay-api-3", secondHold, "cancelled")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_HOLD_MISMATCH"));

        assertThat(fixtures.count("SELECT COUNT(*) FROM unmatched_payment_event WHERE event_id = ? AND reason = ?",
                "evt-api-unknown", "HOLD_NOT_FOUND")).isEqualTo(1);
        assertThat(fixtures.count("SELECT COUNT(*) FROM unmatched_payment_event WHERE event_id = ? AND reason = ?",
                "evt-api-4", "PAYMENT_HOLD_MISMATCH")).isEqualTo(1);
        assertThat(fixtures.paymentStatus("pay-api-3")).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("같은 결제가 서로 다른 선점으로 동시에 승인되어도 한쪽만 판매되고 다른 쪽은 422로 기록된다")
    void samePaymentForTwoHoldsAtOnce() throws Exception {
        for (int round = 0; round < 10; round++) {
            List<Long> seatIds = fixtures.createPerformanceWithSeats(2);
            long performanceId = fixtures.performanceIdOf(seatIds.getFirst());
            String holdA = createHold("payer", performanceId, seatIds.get(0));
            String holdB = createHold("payer", performanceId, seatIds.get(1));
            String paymentId = "pay-shared-" + round;
            String eventA = "evt-shared-a-" + round;
            String eventB = "evt-shared-b-" + round;

            List<Callable<?>> tasks = List.of(
                    () -> mockMvc.perform(post("/payment-events").contentType(MediaType.APPLICATION_JSON)
                            .content(paymentEvent(eventA, paymentId, holdA, "approved"))).andReturn(),
                    () -> mockMvc.perform(post("/payment-events").contentType(MediaType.APPLICATION_JSON)
                            .content(paymentEvent(eventB, paymentId, holdB, "approved"))).andReturn()
            );
            List<Object> results = Concurrently.run(tasks);

            assertThat(results).allMatch(MvcResult.class::isInstance);
            List<Integer> statuses = results.stream()
                    .map(r -> ((MvcResult) r).getResponse().getStatus()).toList();
            assertThat(statuses).containsExactlyInAnyOrder(200, 422);

            boolean aWon = statuses.get(0) == 200;
            String winner = aWon ? holdA : holdB;
            String loserEvent = aWon ? eventB : eventA;
            MvcResult rejected = (MvcResult) results.get(aWon ? 1 : 0);
            assertThat(JsonPath.<String>read(rejected.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("PAYMENT_HOLD_MISMATCH");

            assertThat(fixtures.count("SELECT COUNT(*) FROM payment WHERE payment_id = ? AND hold_id = ?",
                    paymentId, winner)).isEqualTo(1);
            assertThat(fixtures.holdStatus(winner)).isEqualTo("CONFIRMED");
            assertThat(fixtures.holdStatus(aWon ? holdB : holdA)).isEqualTo("ACTIVE");
            assertThat(fixtures.count("SELECT COUNT(*) FROM unmatched_payment_event WHERE event_id = ? AND reason = ?",
                    loserEvent, "PAYMENT_HOLD_MISMATCH")).isEqualTo(1);
        }
    }

    private String createHold(String userId, long performanceId, Long... seatIds) throws Exception {
        MvcResult created = mockMvc.perform(post("/holds")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(performanceId, seatIds)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.holdId");
    }

    private String paymentEvent(String eventId, String paymentId, String holdId, String status) {
        return paymentEvent(eventId, paymentId, holdId, status, "2026-10-18T03:00:30Z");
    }

    private String paymentEvent(String eventId, String paymentId, String holdId, String status, String occurredAt) {
        return """
                {"eventId":"%s","paymentId":"%s","holdId":"%s",
                 "status":"%s","occurredAt":"%s"}
                """.formatted(eventId, paymentId, holdId, status, occurredAt);
    }

    private String holdBody(long performanceId, Long... seatIds) {
        return "{\"performanceId\":%d,\"seatIds\":%s}".formatted(performanceId, List.of(seatIds));
    }
}
