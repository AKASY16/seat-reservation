package com.seatreservation.hold.repository;

import com.seatreservation.hold.domain.SeatHold;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SeatHoldRepository extends JpaRepository<SeatHold, String> {

    Optional<SeatHold> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    /**
     선점 행을 잠그고 읽는다.

     선점 취소, 만료 처리, 결제 이벤트 처리는 모두 이 메서드로 선점 행을 먼저 잠근 뒤 상태를 바꾼다.
     같은 선점에 대한 작업이 여러 서버에서 동시에 들어와도 한 번에 하나씩만 진행되고,
     뒤에 들어온 작업은 앞 작업이 바꾼 상태를 보고 판단한다.
     **/
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from SeatHold h where h.id = :id")
    Optional<SeatHold> findByIdForUpdate(@Param("id") String id);

    @Query("""
            select h.id from SeatHold h
             where h.status = com.seatreservation.hold.domain.SeatHoldStatus.ACTIVE and h.expiresAt <= :now
             order by h.expiresAt
            """)
    List<String> findExpiredActiveIds(@Param("now") LocalDateTime now, Pageable pageable);
}
