package com.seatreservation.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

@SpringBootTest
@ActiveProfiles("test")
@Import({IntegrationTestSupport.TestClockConfig.class, TestFixtures.class})
public abstract class IntegrationTestSupport extends MySqlTestSupport {

    protected static final Instant BASE_TIME = Instant.parse("2026-10-18T03:00:00Z");

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected TestFixtures fixtures;

    @BeforeEach
    void resetClock() {
        clock.set(BASE_TIME);
    }

    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(BASE_TIME);
        }
    }
}
