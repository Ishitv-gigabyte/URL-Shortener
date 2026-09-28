package com.ishitv.urlshortener.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    /** Injected wherever "now" is needed, so tests can pin time. UTC matches the stored created_at values. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
