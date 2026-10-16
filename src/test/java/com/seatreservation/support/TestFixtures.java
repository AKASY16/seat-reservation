package com.seatreservation.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class TestFixtures {

    private final JdbcTemplate jdbcTemplate;

    public TestFixtures(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 테스트끼리 좌석이 섞이지 않도록 테스트마다 공연을 새로 만든다.
    public List<Long> createPerformanceWithSeats(int seatCount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO performance (title, starts_at) VALUES ('테스트 공연', '2026-12-31 19:00:00')",
                    Statement.RETURN_GENERATED_KEYS);
            return ps;
        }, keyHolder);
        long performanceId = keyHolder.getKey().longValue();

        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= seatCount; i++) {
            rows.add(new Object[]{performanceId, "A-" + i});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO seat (performance_id, seat_no, status) VALUES (?, ?, 'AVAILABLE')", rows);
        return jdbcTemplate.queryForList(
                "SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, performanceId);
    }

    public long performanceIdOf(Long seatId) {
        return jdbcTemplate.queryForObject("SELECT performance_id FROM seat WHERE id = ?", Long.class, seatId);
    }

    public Map<String, Object> seat(Long seatId) {
        return jdbcTemplate.queryForMap("SELECT status, hold_id FROM seat WHERE id = ?", seatId);
    }

    public String seatStatus(Long seatId) {
        return jdbcTemplate.queryForObject("SELECT status FROM seat WHERE id = ?", String.class, seatId);
    }

    public String holdStatus(String holdId) {
        return jdbcTemplate.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class, holdId);
    }

    public String paymentStatus(String paymentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payment WHERE payment_id = ?", String.class, paymentId);
    }

    public int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    public <T> T queryForObject(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }
}
