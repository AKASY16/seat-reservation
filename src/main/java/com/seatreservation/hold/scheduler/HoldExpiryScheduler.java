package com.seatreservation.hold.scheduler;

import com.seatreservation.hold.service.SeatHoldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 만료된 선점의 좌석을 주기적으로 되돌린다.

 서버마다 이 작업이 돌아도 된다. 선점 하나씩 행을 잠그고 상태를 다시 확인한 뒤 바꾸므로
 같은 선점을 두 서버가 동시에 집어도 한 번만 처리된다. 분산 락이나 리더 선출을 따로 두지 않았다.
 **/
@Component
@ConditionalOnProperty(name = "reservation.hold.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryScheduler.class);

    private final SeatHoldService seatHoldService;

    public HoldExpiryScheduler(SeatHoldService seatHoldService) {
        this.seatHoldService = seatHoldService;
    }

    @Scheduled(fixedDelayString = "${reservation.hold.expiry.interval}")
    public void expireHolds() {
        int expired = 0;
        for (String holdId : seatHoldService.findExpiredHoldIds()) {
            // 한 건이 실패해도 나머지는 계속 처리한다. 실패한 선점은 다음 주기에 다시 대상이 된다.
            try {
                if (seatHoldService.expire(holdId)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("선점 만료 처리에 실패했습니다. holdId={}", holdId, e);
            }
        }
        if (expired > 0) {
            log.info("만료된 선점 {}건을 정리했습니다.", expired);
        }
    }
}
