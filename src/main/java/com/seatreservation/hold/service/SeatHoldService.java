package com.seatreservation.hold.service;

import com.seatreservation.common.BusinessException;
import com.seatreservation.hold.config.HoldProperties;
import com.seatreservation.hold.domain.SeatHold;
import com.seatreservation.hold.domain.SeatHoldStatus;
import com.seatreservation.hold.dto.HoldRequest;
import com.seatreservation.hold.dto.HoldResponse;
import com.seatreservation.hold.repository.SeatHoldRepository;
import com.seatreservation.seat.repository.SeatRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

@Service
public class SeatHoldService {

    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final HoldProperties holdProperties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public SeatHoldService(SeatHoldRepository seatHoldRepository,
                           SeatRepository seatRepository,
                           HoldProperties holdProperties,
                           PlatformTransactionManager transactionManager,
                           Clock clock) {
        this.seatHoldRepository = seatHoldRepository;
        this.seatRepository = seatRepository;
        this.holdProperties = holdProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     좌석을 선점한다. 요청한 좌석을 전부 잡거나, 하나라도 못 잡으면 아무것도 잡지 않는다.

     트랜잭션을 @Transactional 대신 TransactionTemplate으로 연다.
     같은 Idempotency-Key 요청이 동시에 들어오면 늦은 쪽은 트랜잭션이 롤백되는데,
     그 뒤에 먼저 만들어진 선점을 새 트랜잭션에서 다시 읽어 돌려줘야 하기 때문이다.
     **/
    public HoldResult hold(String userId, String idempotencyKey, HoldRequest request) {
        validate(request);

        if (idempotencyKey != null) {
            Optional<HoldResult> replay = findExisting(userId, idempotencyKey, request);
            if (replay.isPresent()) {
                return replay.get();
            }
        }

        try {
            rejectIfAlreadyTaken(request);
            HoldResponse created = transactionTemplate.execute(status -> createHold(userId, idempotencyKey, request));
            return new HoldResult(created, true);
        } catch (BusinessException | DataIntegrityViolationException e) {
            // 같은 키의 요청이 동시에 들어오면 먼저 커밋한 요청이 좌석을 가져가고,
            // 늦은 요청은 좌석을 못 잡거나 유니크 제약에 걸려 여기로 온다. 먼저 만들어진 선점이 있으면 그것을 돌려준다.
            if (idempotencyKey != null) {
                Optional<HoldResult> replay = findExisting(userId, idempotencyKey, request);
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            throw e;
        }
    }

    /**
     이미 잡히거나 팔린 좌석이 하나라도 있으면 쓰기 트랜잭션을 열지 않고 바로 거절한다.

     인기 좌석에는 실패할 요청이 몰린다. 이 요청들이 전부 트랜잭션을 열고 UPDATE한 뒤 롤백하면
     DB 시간 대부분이 실패 처리에 쓰인다. (README.md 부하 시험 참고) 잠금 없는 조회 한 번으로 먼저 걸러낸다.

     여기서는 잠그지 않으므로 통과한 요청도 createHold의 UPDATE에서 다시 판단한다.
     방금 풀린 좌석을 아직 잡힌 것으로 읽어 거절하는 경우는 그 요청이 조금 먼저 들어온 것과 같은 결과다.
     **/
    private void rejectIfAlreadyTaken(HoldRequest request) {
        int holdable = seatRepository.countHoldable(request.performanceId(), request.seatIds(),
                LocalDateTime.now(clock));
        if (holdable != request.seatIds().size()) {
            throw seatUnavailable();
        }
    }

    private HoldResponse createHold(String userId, String idempotencyKey, HoldRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = now.plus(holdProperties.ttl());

        SeatHold hold = SeatHold.create(request.performanceId(), userId, idempotencyKey,
                request.seatIds(), now, expiresAt);

        // 좌석부터 잡는다. 실패하는 요청이 대부분인 상황에서 선점 행을 먼저 INSERT하면
        // 그 INSERT를 매번 롤백으로 되돌리는 비용이 든다. (README.md 부하 시험 참고)
        int held = seatRepository.holdSeats(request.performanceId(), request.seatIds(),
                hold.getId(), expiresAt, now);
        if (held != request.seatIds().size()) {
            // 예외로 트랜잭션 전체를 롤백한다. 이 요청이 바꾼 좌석이 함께 되돌아간다.
            throw seatUnavailable();
        }
        seatHoldRepository.saveAndFlush(hold);
        return HoldResponse.from(hold);
    }

    private BusinessException seatUnavailable() {
        return new BusinessException(HttpStatus.CONFLICT, "SEAT_UNAVAILABLE",
                "이미 선점되었거나 판매된 좌석이 포함되어 있습니다.");
    }

    private Optional<HoldResult> findExisting(String userId, String idempotencyKey, HoldRequest request) {
        return transactionTemplate.execute(status ->
                seatHoldRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                        .map(existing -> {
                            if (!existing.isSameRequest(request.performanceId(), request.seatIds())) {
                                throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                                        "같은 Idempotency-Key로 다른 요청을 보낼 수 없습니다.");
                            }
                            return new HoldResult(HoldResponse.from(existing), false);
                        }));
    }

    private void validate(HoldRequest request) {
        List<Long> seatIds = request.seatIds();
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_SEAT", "같은 좌석이 중복으로 포함되어 있습니다.");
        }
        if (seatIds.size() > holdProperties.maxSeats()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TOO_MANY_SEATS",
                    "한 번에 최대 " + holdProperties.maxSeats() + "석까지 선점할 수 있습니다.");
        }
    }

    /**
     사용자가 선점을 취소한다. 이미 취소된 선점을 다시 취소해도 성공으로 본다.
     **/
    public void cancel(String holdId, String userId) {
        transactionTemplate.executeWithoutResult(status -> {
            SeatHold hold = seatHoldRepository.findByIdForUpdate(holdId)
                    .filter(found -> found.isOwnedBy(userId))
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND",
                            "선점을 찾을 수 없습니다."));

            LocalDateTime now = LocalDateTime.now(clock);
            switch (hold.getStatus()) {
                case CANCELLED -> {
                }
                case ACTIVE -> {
                    if (hold.isExpiredAt(now)) {
                        throw new BusinessException(HttpStatus.CONFLICT, "HOLD_EXPIRED", "이미 만료된 선점입니다.");
                    }
                    hold.cancel(now);
                    seatRepository.releaseHeld(hold.getId(), hold.getSeatIds());
                }
                case EXPIRED -> throw new BusinessException(HttpStatus.CONFLICT, "HOLD_EXPIRED", "이미 만료된 선점입니다.");
                case CONFIRMED -> throw new BusinessException(HttpStatus.CONFLICT, "HOLD_CONFIRMED",
                        "결제가 완료된 선점은 취소할 수 없습니다.");
            }
        });
    }

    /**
     만료 시각이 지난 선점 하나를 만료 처리한다. 처리했으면 true.

     선점 행을 잠근 뒤 상태를 다시 확인하므로, 만료 작업이 여러 곳에서 동시에 돌거나
     같은 선점에 결제 승인이 동시에 들어와도 먼저 잠근 쪽만 상태를 바꾼다.
     **/
    public boolean expire(String holdId) {
        Boolean expired = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            Optional<SeatHold> found = seatHoldRepository.findByIdForUpdate(holdId);
            if (found.isEmpty()) {
                return false;
            }
            SeatHold hold = found.get();
            if (hold.getStatus() != SeatHoldStatus.ACTIVE || !hold.isExpiredAt(now)) {
                return false;
            }
            hold.expire(now);
            seatRepository.releaseHeld(hold.getId(), hold.getSeatIds());
            return true;
        });
        return Boolean.TRUE.equals(expired);
    }

    public List<String> findExpiredHoldIds() {
        return seatHoldRepository.findExpiredActiveIds(LocalDateTime.now(clock),
                PageRequest.of(0, holdProperties.expiry().batchSize()));
    }
}
