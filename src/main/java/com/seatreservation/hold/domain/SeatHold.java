package com.seatreservation.hold.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Entity
@Table(name = "seat_hold")
public class SeatHold implements Persistable<String> {

    // id를 직접 만들어 넣으므로, 이 표시가 없으면 save()가 INSERT 전에 같은 id가 있는지 SELECT부터 한다.
    @Transient
    private boolean isNew = true;

    @Id
    private String id;

    @Column(nullable = false)
    private Long performanceId;

    @Column(nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatHoldStatus status;

    private String idempotencyKey;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @ElementCollection
    @CollectionTable(name = "seat_hold_item", joinColumns = @JoinColumn(name = "hold_id"))
    @Column(name = "seat_id")
    @OrderBy // DB에서 다시 읽어도 좌석 id 오름차순을 유지한다
    private Set<Long> seatIds = new TreeSet<>();

    protected SeatHold() {
    }

    public static SeatHold create(Long performanceId, String userId, String idempotencyKey,
                                  List<Long> seatIds, LocalDateTime now, LocalDateTime expiresAt) {
        SeatHold hold = new SeatHold();
        hold.id = UUID.randomUUID().toString();
        hold.performanceId = performanceId;
        hold.userId = userId;
        hold.idempotencyKey = idempotencyKey;
        hold.status = SeatHoldStatus.ACTIVE;
        hold.seatIds.addAll(seatIds);
        hold.expiresAt = expiresAt;
        hold.createdAt = now;
        hold.updatedAt = now;
        return hold;
    }

    public boolean isExpiredAt(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isOwnedBy(String userId) {
        return this.userId.equals(userId);
    }

    public boolean isSameRequest(Long performanceId, List<Long> seatIds) {
        return this.performanceId.equals(performanceId) && this.seatIds.equals(new TreeSet<>(seatIds));
    }

    public void cancel(LocalDateTime now) {
        changeStatus(SeatHoldStatus.CANCELLED, now);
    }

    public void expire(LocalDateTime now) {
        changeStatus(SeatHoldStatus.EXPIRED, now);
    }

    public void confirm(LocalDateTime now) {
        changeStatus(SeatHoldStatus.CONFIRMED, now);
    }

    private void changeStatus(SeatHoldStatus status, LocalDateTime now) {
        this.status = status;
        this.updatedAt = now;
    }

    @Override
    public String getId() {
        return id;
    }

    public Long getPerformanceId() {
        return performanceId;
    }

    public String getUserId() {
        return userId;
    }

    public SeatHoldStatus getStatus() {
        return status;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public Set<Long> getSeatIds() {
        return seatIds;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
