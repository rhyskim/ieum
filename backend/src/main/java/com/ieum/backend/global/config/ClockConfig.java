package com.ieum.backend.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시각 주입용 Clock. 테스트는 @Primary Clock으로 바꿔 "19시에 꺼졌다가 19:30에 켜짐" 같은 상황을 재현한다. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
