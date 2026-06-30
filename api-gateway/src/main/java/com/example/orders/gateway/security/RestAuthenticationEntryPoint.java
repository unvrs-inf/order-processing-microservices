package com.example.orders.gateway.security;

import com.example.orders.gateway.error.GatewayErrorWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Формирует ответ 401 при отсутствующем или неверном токене. */
@Component
class RestAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final GatewayErrorWriter errorWriter;

    RestAuthenticationEntryPoint(GatewayErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        // Сообщение исключения содержит подробности проверки подписи или срока и не включается в ответ.
        return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "Authentication required");
    }
}
