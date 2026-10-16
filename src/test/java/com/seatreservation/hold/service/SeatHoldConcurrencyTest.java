package com.seatreservation.hold.service;

import com.seatreservation.common.BusinessException;
import com.seatreservation.hold.config.HoldProperties;
import com.seatreservation.hold.dto.HoldRequest;
import com.seatreservation.hold.dto.HoldResponse;
import com.seatreservation.hold.repository.SeatHoldRepository;
import com.seatreservation.seat.repository.SeatRepository;
import com.seatreservation.support.Concurrently;
import com.seatreservation.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SeatHoldConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    SeatHoldService seatHoldService;

    @Autowired
    DataSource dataSource;

    @Autowired
    SeatHoldRepository seatHoldRepository;

    @Autowired
    SeatRepository seatRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    HoldProperties holdProperties;

    @Test
    @DisplayName("같은 좌석에 선점 요청 50개가 동시에 들어와도 하나만 성공한다")
    void onlyOneHoldSucceedsForSameSeat() throws Exception {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
        Long seatId = seatIds.getFirst();
        long performanceId = fixtures.performanceIdOf(seatId);

        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String userId = "user-" + i;
            tasks.add(() -> seatHoldService.hold(userId, null, new HoldRequest(performanceId, List.of(seatId))));
        }
        List<Object> results = Concurrently.run(tasks);

        List<HoldResult> succeeded = results.stream()
                .filter(HoldResult.class::isInstance).map(HoldResult.class::cast).toList();
        assertThat(succeeded).hasSize(1);
        assertThat(results).filteredOn(BusinessException.class::isInstance)
                .hasSize(49)
                .allSatisfy(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("SEAT_UNAVAILABLE"));

        assertThat(fixtures.seat(seatId))
                .containsEntry("status", "HELD")
                .containsEntry("hold_id", succeeded.getFirst().hold().holdId());
        assertThat(fixtures.count("SELECT COUNT(*) FROM seat_hold WHERE performance_id = ?", performanceId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("여러 좌석 중 하나라도 이미 선점되어 있으면 나머지 좌석도 선점되지 않는다")
    void multiSeatHoldIsAllOrNothing() {
        // 이 경우는 쓰기 전에 하는 사전 조회에서 걸러진다. UPDATE까지 간 뒤 되돌리는 경우는 바로 아래 테스트에서 본다.
        List<Long> seatIds = fixtures.createPerformanceWithSeats(3);
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());
        seatHoldService.hold("first", null, new HoldRequest(performanceId, List.of(seatIds.get(1))));

        HoldRequest request = new HoldRequest(performanceId, seatIds);
        BusinessException error = catchBusiness(() -> seatHoldService.hold("second", null, request));

        assertThat(error.getCode()).isEqualTo("SEAT_UNAVAILABLE");
        assertThat(fixtures.seatStatus(seatIds.get(0))).isEqualTo("AVAILABLE");
        assertThat(fixtures.seatStatus(seatIds.get(2))).isEqualTo("AVAILABLE");
        assertThat(fixtures.count("SELECT COUNT(*) FROM seat_hold WHERE user_id = 'second' AND performance_id = ?",
                performanceId)).isZero();
    }

    @Test
    @DisplayName("사전 조회를 통과한 뒤 UPDATE에서 좌석 하나를 놓치면, 이미 바꾼 좌석까지 되돌리고 거절한다")
    void partialUpdateIsRolledBack() throws Exception {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(2);
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());
        Long first = seatIds.get(0);
        Long second = seatIds.get(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection();
             Connection observer = dataSource.getConnection()) {
            // 다른 트랜잭션이 두 번째 좌석을 잡아 두고 아직 커밋하지 않는다.
            // 사전 조회는 커밋된 값(AVAILABLE)을 읽어 통과하고, UPDATE는 첫 번째 좌석을 바꾼 뒤 두 번째 좌석에서 기다린다.
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement(
                    "UPDATE seat SET status = 'HELD', hold_id = 'other', held_until = '2026-10-18 04:00:00' WHERE id = ?")) {
                ps.setLong(1, second);
                ps.executeUpdate();
            }

            Future<HoldResult> holding = executor.submit(() ->
                    seatHoldService.hold("user", null, new HoldRequest(performanceId, seatIds)));

            // 커밋되지 않은 값까지 읽어서, 선점 요청이 첫 번째 좌석을 이미 바꿨는지 확인한다.
            observer.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
            String firstSeatHolder = waitForHolder(observer, first);
            assertThat(firstSeatHolder).isNotNull().isNotEqualTo("other");

            other.commit();

            ExecutionException error = catchThrowableOfType(ExecutionException.class,
                    () -> holding.get(30, TimeUnit.SECONDS));
            assertThat(error.getCause()).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) error.getCause()).getCode()).isEqualTo("SEAT_UNAVAILABLE");
        } finally {
            executor.shutdownNow();
        }

        assertThat(fixtures.seat(first))
                .containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null);
        assertThat(fixtures.seat(second))
                .containsEntry("status", "HELD")
                .containsEntry("hold_id", "other");
        assertThat(fixtures.count("SELECT COUNT(*) FROM seat_hold WHERE user_id = 'user' AND performance_id = ?",
                performanceId)).isZero();
    }

    @Test
    @DisplayName("겹치는 여러 좌석 선점이 동시에 들어와도 성공한 선점은 좌석을 모두 가지고, 실패한 선점은 하나도 가지지 않는다")
    void overlappingMultiSeatHoldsKeepAllOrNothing() throws Exception {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(10);
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());

        // 1~4석짜리 요청 40개가 좌석 10개를 두고 겹치게 경쟁한다.
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int size = i % 4 + 1;
            int start = (i * 3) % (seatIds.size() - size + 1);
            List<Long> wanted = seatIds.subList(start, start + size);
            // 같은 좌석 집합이라도 순서를 뒤집어 보내 잠금 순서가 요청 순서에 좌우되지 않는지 함께 본다.
            List<Long> ordered = i % 2 == 0 ? wanted : wanted.reversed();
            String userId = "user-" + i;
            tasks.add(() -> seatHoldService.hold(userId, null, new HoldRequest(performanceId, ordered)));
        }
        List<Object> results = Concurrently.run(tasks);

        assertThat(results).allMatch(r -> r instanceof HoldResult
                || (r instanceof BusinessException e && e.getCode().equals("SEAT_UNAVAILABLE")));

        List<HoldResult> succeeded = results.stream()
                .filter(HoldResult.class::isInstance).map(HoldResult.class::cast).toList();
        int heldSeats = 0;
        for (HoldResult result : succeeded) {
            for (Long seatId : result.hold().seatIds()) {
                assertThat(fixtures.seat(seatId)).containsEntry("hold_id", result.hold().holdId());
            }
            heldSeats += result.hold().seatIds().size();
        }
        // 좌석 하나가 두 선점에 걸려 있다면 위 검사에서 걸리거나 이 합계가 맞지 않는다.
        assertThat(fixtures.count("SELECT COUNT(*) FROM seat WHERE performance_id = ? AND status = 'HELD'", performanceId))
                .isEqualTo(heldSeats);
        assertThat(fixtures.count("SELECT COUNT(*) FROM seat_hold WHERE performance_id = ?", performanceId))
                .isEqualTo(succeeded.size());
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 동시에 여러 번 보내도 선점은 하나만 만들어지고 모두 같은 선점을 받는다")
    void sameIdempotencyKeyCreatesOneHold() throws Exception {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(2);
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());
        HoldRequest request = new HoldRequest(performanceId, seatIds);

        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> seatHoldService.hold("retry-user", "key-1", request));
        }
        List<Object> results = Concurrently.run(tasks);

        assertThat(results).allMatch(HoldResult.class::isInstance);
        List<HoldResult> holds = results.stream().map(HoldResult.class::cast).toList();
        assertThat(holds).extracting(h -> h.hold().holdId()).containsOnly(holds.getFirst().hold().holdId());
        assertThat(holds).filteredOn(HoldResult::created).hasSize(1);
        assertThat(fixtures.count("SELECT COUNT(*) FROM seat_hold WHERE user_id = 'retry-user'")).isEqualTo(1);
    }

    @Test
    @DisplayName("대소문자만 다른 사용자는 같은 Idempotency-Key를 써도 서로 다른 선점을 받는다")
    void idempotencyKeyBelongsToExactUserId() {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(2);
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());

        HoldResult upper = seatHoldService.hold("Alice", "key-3", new HoldRequest(performanceId, List.of(seatIds.get(0))));
        HoldResult lower = seatHoldService.hold("alice", "key-3", new HoldRequest(performanceId, List.of(seatIds.get(1))));

        assertThat(upper.created()).isTrue();
        assertThat(lower.created()).isTrue();
        assertThat(lower.hold().holdId()).isNotEqualTo(upper.hold().holdId());
        assertThat(fixtures.seat(seatIds.get(1))).containsEntry("hold_id", lower.hold().holdId());
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 다른 좌석을 동시에 요청하면 선점은 하나만 만들어지고 나머지는 거절된다")
    void sameKeyWithDifferentSeatsAtOnce() throws Exception {
        for (int round = 0; round < 10; round++) {
            List<Long> seatIds = fixtures.createPerformanceWithSeats(2);
            long performanceId = fixtures.performanceIdOf(seatIds.getFirst());
            String userId = "key-user-" + round;

            List<Callable<?>> tasks = List.of(
                    () -> seatHoldService.hold(userId, "key-4", new HoldRequest(performanceId, List.of(seatIds.get(0)))),
                    () -> seatHoldService.hold(userId, "key-4", new HoldRequest(performanceId, List.of(seatIds.get(1))))
            );
            List<Object> results = Concurrently.run(tasks);

            assertThat(results).filteredOn(HoldResult.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(BusinessException.class::isInstance)
                    .hasSize(1)
                    .allSatisfy(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
            // 거절된 요청이 잡았던 좌석은 롤백되어 남지 않는다.
            assertThat(fixtures.count("SELECT COUNT(*) FROM seat WHERE performance_id = ? AND status = 'HELD'",
                    performanceId)).isEqualTo(1);
            assertThat(fixtures.count("SELECT COUNT(*) FROM seat_hold WHERE user_id = ?", userId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 다른 좌석을 요청하면 거절한다")
    void reusedIdempotencyKeyWithDifferentRequestIsRejected() {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(2);
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());
        seatHoldService.hold("user", "key-2", new HoldRequest(performanceId, List.of(seatIds.get(0))));

        BusinessException error = catchBusiness(() ->
                seatHoldService.hold("user", "key-2", new HoldRequest(performanceId, List.of(seatIds.get(1)))));

        assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(fixtures.seatStatus(seatIds.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("만료 작업이 여러 곳에서 동시에 돌아도 같은 선점은 한 번만 만료 처리된다")
    void concurrentExpiryProcessesHoldOnce() throws Exception {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(2);
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());
        HoldResponse hold = seatHoldService.hold("user", null, new HoldRequest(performanceId, seatIds)).hold();
        clock.set(hold.expiresAt());

        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> seatHoldService.expire(hold.holdId()));
        }
        List<Object> results = Concurrently.run(tasks);

        assertThat(results).containsOnlyOnce(true).filteredOn(r -> !Boolean.TRUE.equals(r)).containsOnly(false);
        assertThat(fixtures.holdStatus(hold.holdId())).isEqualTo("EXPIRED");
        assertThat(seatIds).allSatisfy(id -> assertThat(fixtures.seatStatus(id)).isEqualTo("AVAILABLE"));
    }

    @Test
    @DisplayName("만료 시각이 지난 좌석은 만료 작업이 돌기 전에도 다른 사용자가 선점할 수 있다")
    void expiredSeatCanBeHeldBeforeExpiryJobRuns() {
        List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());
        HoldRequest request = new HoldRequest(performanceId, seatIds);
        HoldResponse first = seatHoldService.hold("first", null, request).hold();

        clock.set(first.expiresAt().minusMillis(1));
        assertThat(catchBusiness(() -> seatHoldService.hold("second", null, request)).getCode())
                .isEqualTo("SEAT_UNAVAILABLE");

        clock.set(first.expiresAt());
        HoldResponse second = seatHoldService.hold("second", null, request).hold();
        assertThat(fixtures.seat(seatIds.getFirst())).containsEntry("hold_id", second.holdId());

        // 뒤늦게 만료 작업이 돌아도 이미 다른 선점으로 넘어간 좌석은 건드리지 않는다.
        assertThat(seatHoldService.expire(first.holdId())).isTrue();
        assertThat(fixtures.seat(seatIds.getFirst()))
                .containsEntry("status", "HELD")
                .containsEntry("hold_id", second.holdId());
    }

    @Test
    @DisplayName("선점 유지 시간에 마이크로초보다 작은 단위가 있어도 응답과 저장된 만료 시각이 같다")
    void ttlFinerThanMicrosKeepsExpiryConsistent() {
        HoldProperties nanoTtl = new HoldProperties(Duration.parse("PT0.050000789S"),
                holdProperties.maxSeats(), holdProperties.expiry());
        SeatHoldService service = new SeatHoldService(seatHoldRepository, seatRepository, nanoTtl,
                transactionManager, clock);
        List<Long> seatIds = fixtures.createPerformanceWithSeats(1);
        long performanceId = fixtures.performanceIdOf(seatIds.getFirst());

        HoldResponse hold = service.hold("user", null, new HoldRequest(performanceId, seatIds)).hold();

        LocalDateTime stored = fixtures.queryForObject("SELECT expires_at FROM seat_hold WHERE id = ?",
                LocalDateTime.class, hold.holdId());
        assertThat(stored.toInstant(ZoneOffset.UTC)).isEqualTo(hold.expiresAt());
    }

    @Test
    @DisplayName("만료 처리와 만료된 좌석을 가져가는 새 선점이 동시에 돌아도 데드락 없이 끝난다")
    void expiryAndTakeoverDoNotDeadlock() throws Exception {
        // 새 선점이 만료된 선점 두 개에 걸친 좌석을 잡도록 한 칸씩 어긋나게 요청한다.
        // 좌석을 되돌릴 때 잠금 순서를 맞추기 전에는 60회 중 1~2회 데드락이 났다.
        for (int round = 0; round < 60; round++) {
            clock.set(BASE_TIME);
            List<Long> seatIds = fixtures.createPerformanceWithSeats(20);
            long performanceId = fixtures.performanceIdOf(seatIds.getFirst());
            List<HoldResponse> oldHolds = new ArrayList<>();
            for (int i = 0; i < 20; i += 2) {
                oldHolds.add(seatHoldService.hold("old-" + i, null,
                        new HoldRequest(performanceId, seatIds.subList(i, i + 2))).hold());
            }
            clock.set(oldHolds.getFirst().expiresAt().plusSeconds(1));

            List<Callable<?>> tasks = new ArrayList<>();
            for (HoldResponse old : oldHolds) {
                tasks.add(() -> seatHoldService.expire(old.holdId()));
            }
            for (int i = 1; i < 19; i += 2) {
                List<Long> wanted = seatIds.subList(i, i + 2);
                String userId = "new-" + i;
                tasks.add(() -> seatHoldService.hold(userId, null, new HoldRequest(performanceId, wanted)));
            }
            List<Object> results = Concurrently.run(tasks);

            // 만료 처리는 모두 성공하고, 새 선점은 만료된 좌석을 모두 가져간다.
            assertThat(results.subList(0, oldHolds.size())).containsOnly(true);
            List<Object> newHolds = results.subList(oldHolds.size(), results.size());
            assertThat(newHolds).allMatch(HoldResult.class::isInstance);

            assertThat(oldHolds).allSatisfy(old -> assertThat(fixtures.holdStatus(old.holdId())).isEqualTo("EXPIRED"));
            for (Object result : newHolds) {
                HoldResponse hold = ((HoldResult) result).hold();
                for (Long seatId : hold.seatIds()) {
                    assertThat(fixtures.seat(seatId))
                            .containsEntry("status", "HELD")
                            .containsEntry("hold_id", hold.holdId());
                }
            }
            // 양 끝 좌석은 새 선점이 원하지 않았으므로 만료 처리로 풀려 있어야 한다.
            assertThat(fixtures.count("SELECT COUNT(*) FROM seat WHERE performance_id = ? AND status = 'HELD'",
                    performanceId)).isEqualTo(newHolds.size() * 2);
            assertThat(fixtures.seatStatus(seatIds.getFirst())).isEqualTo("AVAILABLE");
            assertThat(fixtures.seatStatus(seatIds.getLast())).isEqualTo("AVAILABLE");
        }
    }

    // 좌석이 다른 선점으로 바뀔 때까지 짧게 기다리며 hold_id를 읽는다.
    private String waitForHolder(Connection observer, Long seatId) throws Exception {
        try (PreparedStatement ps = observer.prepareStatement("SELECT hold_id FROM seat WHERE id = ?")) {
            ps.setLong(1, seatId);
            for (int i = 0; i < 100; i++) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    String holdId = rs.getString(1);
                    if (holdId != null) {
                        return holdId;
                    }
                }
                Thread.sleep(50);
            }
        }
        return null;
    }

    private BusinessException catchBusiness(Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("BusinessException이 발생해야 합니다.");
    }
}
