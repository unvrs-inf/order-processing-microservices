package com.example.orders.gateway.correlation;

import com.example.orders.gateway.security.GatewayHeaders;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Добавляет идентификатор корреляции и записывает строку журнала доступа на запрос.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationId.sanitise(
                exchange.getRequest().getHeaders().getFirst(GatewayHeaders.CORRELATION_ID));

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(GatewayHeaders.CORRELATION_ID, correlationId))
                .build();
        ServerWebExchange mutated = exchange.mutate().request(request).build();

        // Перед отправкой ответа восстанавливаем идентификатор, если ответ сервиса содержал свой заголовок.
        mutated.getResponse().getHeaders().set(GatewayHeaders.CORRELATION_ID, correlationId);
        mutated.getResponse().beforeCommit(() -> {
            mutated.getResponse().getHeaders().set(GatewayHeaders.CORRELATION_ID, correlationId);
            return Mono.empty();
        });

        long startedAt = System.nanoTime();
        return chain.filter(mutated)
                .doFinally(signal -> logAccess(mutated, correlationId, startedAt));
    }

    private static void logAccess(ServerWebExchange exchange, String correlationId, long startedAt) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        log.info("{} {} -> {} ({} ms) correlationId={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                status == null ? "-" : status.value(),
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                correlationId);
    }
}
