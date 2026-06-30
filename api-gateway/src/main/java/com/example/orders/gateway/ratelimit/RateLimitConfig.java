package com.example.orders.gateway.ratelimit;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Подключает лимитер Redis для {@code RequestRateLimiter}, который пропускает запрос при сбое Redis.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

    @Bean
    KeyResolver userOrIpKeyResolver() {
        return new UserOrIpKeyResolver();
    }

    /**
     * @param redisTemplate реактивный клиент Redis
     * @param script сценарий {@code request_rate_limiter.lua}
     * @param configurationService читает параметры {@code redis-rate-limiter.*}
     * @return лимитер для фильтра {@code RequestRateLimiter}
     */
    @Bean
    @ConfigurationProperties("spring.cloud.gateway.server.webflux.redis-rate-limiter")
    RedisRateLimiter redisRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script,
            ConfigurationService configurationService) {
        return new FailOpenRedisRateLimiter(redisTemplate, script, configurationService);
    }
}
