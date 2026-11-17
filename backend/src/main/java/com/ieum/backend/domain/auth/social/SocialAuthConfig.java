package com.ieum.backend.domain.auth.social;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(SocialAuthProperties.class)
public class SocialAuthConfig {

    /** 제공자 호출용 빌더. 타임아웃을 두어 제공자 지연이 서버 스레드를 붙잡지 않게 한다. */
    @Bean
    public RestClient.Builder socialRestClientBuilder(SocialAuthProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        return RestClient.builder().requestFactory(factory);
    }
}
