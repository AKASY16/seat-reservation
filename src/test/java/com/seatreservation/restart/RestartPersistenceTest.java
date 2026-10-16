package com.seatreservation.restart;

import com.seatreservation.SeatReservationApplication;
import com.seatreservation.hold.dto.HoldRequest;
import com.seatreservation.hold.dto.HoldResponse;
import com.seatreservation.hold.service.SeatHoldService;
import com.seatreservation.payment.domain.PaymentEventStatus;
import com.seatreservation.payment.dto.PaymentEventRequest;
import com.seatreservation.payment.service.PaymentEventService;
import com.seatreservation.seat.domain.SeatStatus;
import com.seatreservation.seat.dto.PerformanceSeatsResponse;
import com.seatreservation.seat.dto.SeatResponse;
import com.seatreservation.seat.service.SeatQueryService;
import com.seatreservation.support.MySqlTestSupport;
import com.seatreservation.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class RestartPersistenceTest extends MySqlTestSupport {

    @Test
    @DisplayName("애플리케이션을 종료하고 다시 시작해도 판매 확정된 좌석은 SOLD로 남아 있다")
    void soldSeatsSurviveRestart() {
        long performanceId;
        List<Long> seatIds;

        // 첫 번째 실행: 좌석을 선점하고 결제 승인으로 판매 확정한 뒤 애플리케이션을 종료한다.
        try (ConfigurableApplicationContext first = startApplication()) {
            TestFixtures fixtures = new TestFixtures(first.getBean(JdbcTemplate.class));
            seatIds = fixtures.createPerformanceWithSeats(3);
            performanceId = fixtures.performanceIdOf(seatIds.getFirst());

            HoldResponse hold = first.getBean(SeatHoldService.class)
                    .hold("user", null, new HoldRequest(performanceId, seatIds.subList(0, 2)))
                    .hold();
            first.getBean(PaymentEventService.class).handle(new PaymentEventRequest(
                    "evt-restart", "pay-restart", hold.holdId(), PaymentEventStatus.APPROVED, Instant.now()));
        }

        // 두 번째 실행: 새 커넥션 풀과 새 영속성 컨텍스트로 DB에서 다시 읽는다.
        try (ConfigurableApplicationContext second = startApplication()) {
            PerformanceSeatsResponse response = second.getBean(SeatQueryService.class).getSeats(performanceId);

            assertThat(response.seats())
                    .extracting(SeatResponse::seatId, SeatResponse::status)
                    .containsExactly(
                            tuple(seatIds.get(0), SeatStatus.SOLD),
                            tuple(seatIds.get(1), SeatStatus.SOLD),
                            tuple(seatIds.get(2), SeatStatus.AVAILABLE));
        }
    }

    private ConfigurableApplicationContext startApplication() {
        // properties()로 넘기면 기본값 취급이라 application.yaml의 datasource 설정에 밀린다.
        // 실제 실행처럼 명령행 인자로 넘긴다.
        return new SpringApplicationBuilder(SeatReservationApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + mysql().getJdbcUrl() + "?connectionTimeZone=UTC",
                        "--spring.datasource.username=" + mysql().getUsername(),
                        "--spring.datasource.password=" + mysql().getPassword(),
                        "--reservation.hold.expiry.enabled=false");
    }
}
