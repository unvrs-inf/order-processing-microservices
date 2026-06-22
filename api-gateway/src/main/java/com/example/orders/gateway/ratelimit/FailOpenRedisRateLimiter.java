package com.example.orders.gateway.ratelimit;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

/**
 * Лимитер на основе сценария Lua Spring Cloud Gateway. При сбое Redis запрос проходит с записью предупреждения.
 */
public class FailOpenRedisRateLimiter extends RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FailOpenRedisRateLimiter.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> script;

    /**
     * @param redisTemplate реактивный клиент Redis
     * @param script скрипт проверки лимитов из Spring Cloud Gateway
     * @param configurationService читает параметры {@code redis-rate-limiter.*} маршрута
     */
    public FailOpenRedisRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            RedisScript<List<Long>> script,
            ConfigurationService configurationService) {
        super(redisTemplate, script, configurationService);
        this.redisTemplate = redisTemplate;
        this.script = script;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        Config config = resolveConfig(routeId);
        List<String> keys = keys(routeId, id);
        List<String> args = List.of(
                String.valueOf(config.getReplenishRate()),
                String.valueOf(config.getBurstCapacity()),
                "",
                String.valueOf(config.getRequestedTokens()));

        return redisTemplate.execute(script, keys, args)
                .reduce(new ArrayList<Long>(), (all, chunk) -> {
                    all.addAll(chunk);
                    return all;
                })
                .map(results -> new Response(results.get(0) == 1L, getHeaders(config, results.get(1))))
                .onErrorResume(error -> failOpen(routeId, config, error));
    }

    private Mono<Response> failOpen(String routeId, Config config, Throwable error) {
        log.warn("Rate limiting unavailable for route '{}', allowing the request through: {}",
                routeId, error.toString());
        return Mono.just(new Response(true, getHeaders(config, -1L)));
    }

    /**
     * Приоритет имеют настройки {@code redis-rate-limiter.*} маршрута, затем настройки по умолчанию.
     */
    private Config resolveConfig(String routeId) {
        Config config = getConfig().get(routeId);
        if (config == null) {
            config = getConfig().get(RouteDefinitionRouteLocator.DEFAULT_FILTERS);
        }
        if (config == null) {
            throw new IllegalArgumentException(
                    "No redis-rate-limiter configuration found for route " + routeId + " or defaultFilters");
        }
        return config;
    }

    /** Ключи Redis разделены по маршрутам и клиентам. */
    private static List<String> keys(String routeId, String id) {
        // Хеш-тег в фигурных скобках помещает оба ключа в один слот Redis Cluster.
        String prefix = "request_rate_limiter.{" + routeId + "." + id + "}.";
        return List.of(prefix + "tokens", prefix + "timestamp");
    }

    @Override
    public String toString() {
        return "FailOpenRedisRateLimiter" + getConfig();
    }
}
