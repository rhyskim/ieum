package com.ieum.backend.domain.question.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 질문 배정 설정(ADR-0006). */
@ConfigurationProperties(prefix = "ieum.question")
public record QuestionProperties(
    /** 그룹이 받을 수 있는 남은 질문이 이 값 이하이면 경고 로그를 남긴다. */
    Integer lowStockThreshold
) {
    public QuestionProperties {
        lowStockThreshold = lowStockThreshold != null ? lowStockThreshold : 30;
    }
}
