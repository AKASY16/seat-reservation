package com.seatreservation.api;

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
                .andExpect(status().isBadRequest());

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

    private String holdBody(long performanceId, Long... seatIds) {
        return "{\"performanceId\":%d,\"seatIds\":%s}".formatted(performanceId, List.of(seatIds));
    }
}
