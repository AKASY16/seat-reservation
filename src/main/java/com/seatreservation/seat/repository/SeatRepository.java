package com.seatreservation.seat.repository;

import com.seatreservation.seat.domain.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByPerformanceIdOrderByIdAsc(Long performanceId);

    /**
     비어 있는 좌석만 골라 한 번에 선점 상태로 바꾸고, 바뀐 행 수를 돌려준다.

     조건 검사와 변경이 UPDATE 한 문장 안에서 일어나므로, 같은 좌석을 동시에 노리는 요청이 여러 개여도
     InnoDB 행 잠금 때문에 한 요청이 끝날 때까지 나머지는 기다린다. 기다린 요청은 이미 바뀐 값으로
     WHERE를 다시 평가하므로 조건에 맞지 않아 0행이 된다.

     PK 범위로 행을 잠그기 때문에 여러 좌석을 잡는 요청끼리도 항상 id 오름차순으로 잠근다.
     잠금 순서가 같아서 서로 교차해 기다리는 데드락이 생기지 않는다.

     만료 시각이 지난 HELD 좌석도 빈 좌석으로 보고 잡는다. 만료 작업이 아직 돌지 않았어도 바로 다시 팔 수 있다.
     **/
    @Modifying
    @Query(value = """
            UPDATE seat
               SET status = 'HELD', hold_id = :holdId, held_until = :heldUntil
             WHERE id IN (:seatIds)
               AND performance_id = :performanceId
               AND (status = 'AVAILABLE' OR (status = 'HELD' AND held_until <= :now))
            """, nativeQuery = true)
    int holdSeats(@Param("performanceId") Long performanceId,
                  @Param("seatIds") Collection<Long> seatIds,
                  @Param("holdId") String holdId,
                  @Param("heldUntil") LocalDateTime heldUntil,
                  @Param("now") LocalDateTime now);

    /**
     선점을 취소하거나 만료할 때 아직 이 선점이 잡고 있는 좌석만 되돌린다.
     이미 다른 선점으로 넘어간 좌석은 hold_id가 달라서 건드리지 않는다.

     UPDATE 전에 좌석 행을 PK 순서로 먼저 잠근다.
     hold_id 조건의 UPDATE는 hold_id 인덱스를 먼저 잠그고 좌석 행을 나중에 잠그는데,
     새 선점(holdSeats)은 반대로 좌석 행을 먼저 잠그고 hold_id 인덱스를 나중에 고친다.
     만료된 좌석을 새 선점이 가져가는 순간 만료 처리가 같이 돌면 둘이 서로를 기다리는 데드락이 생긴다.
     좌석 행을 먼저 잠그면 두 경로의 잠금 순서가 같아진다.
     **/
    default int releaseHeld(String holdId, Collection<Long> seatIds) {
        findAllByIdForUpdate(seatIds);
        return releaseHeldByHoldId(holdId);
    }

    // 결제가 취소되어 판매를 되돌릴 때 쓴다. 잠금 순서는 releaseHeld와 같은 이유로 맞춘다.
    default int releaseSold(String holdId, Collection<Long> seatIds) {
        findAllByIdForUpdate(seatIds);
        return releaseSoldByHoldId(holdId);
    }

    @Modifying(flushAutomatically = true)
    @Query("""
            update Seat s
               set s.status = com.seatreservation.seat.domain.SeatStatus.AVAILABLE, s.holdId = null, s.heldUntil = null
             where s.holdId = :holdId and s.status = com.seatreservation.seat.domain.SeatStatus.HELD
            """)
    int releaseHeldByHoldId(@Param("holdId") String holdId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update Seat s
               set s.status = com.seatreservation.seat.domain.SeatStatus.AVAILABLE, s.holdId = null, s.heldUntil = null
             where s.holdId = :holdId and s.status = com.seatreservation.seat.domain.SeatStatus.SOLD
            """)
    int releaseSoldByHoldId(@Param("holdId") String holdId);

    // 결제 승인 시 좌석을 판매 확정하기 전에 잠근다. id 순서로 잠가 선점 요청과 잠금 순서를 맞춘다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id in :seatIds order by s.id")
    List<Seat> findAllByIdForUpdate(@Param("seatIds") Collection<Long> seatIds);
}
