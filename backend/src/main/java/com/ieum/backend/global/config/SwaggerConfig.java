package com.ieum.backend.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI ieumOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("이음 (Ieum) API Server")
                .description("부모님과 함께하는 하루 한 질문 기록 앱 백엔드 API")
                .version("v1.0.0"));
    }
}
