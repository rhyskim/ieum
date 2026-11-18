package com.ieum.backend.domain.notification.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Slf4j
@Configuration
@EnableAsync
public class PushAsyncConfig {

    public static final String PUSH_EXECUTOR = "pushExecutor";

    /**
     * 푸시 전용 풀. 큐가 가득 차면 요청 스레드를 막지 않고 알림을 버린다(푸시는 보조 채널이고
     * 기준은 앱 안 목록이다). 호출자에게 예외가 전파되어 업무 응답이 깨지는 일도 없다.
     */
    @Bean(name = PUSH_EXECUTOR)
    public Executor pushExecutor(PushProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("push-");
        executor.setCorePoolSize(properties.poolSize());
        executor.setMaxPoolSize(properties.poolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setRejectedExecutionHandler((Runnable task, ThreadPoolExecutor pool) ->
            log.warn("푸시 큐가 가득 차 알림 1건을 버립니다."));
        executor.initialize();
        return executor;
    }
}
