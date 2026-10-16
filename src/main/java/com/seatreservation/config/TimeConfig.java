package com.seatreservation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    // 현재 시각을 Clock으로 주입받는다. 테스트에서 시각을 고정하거나 앞당기기 위해서다.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
