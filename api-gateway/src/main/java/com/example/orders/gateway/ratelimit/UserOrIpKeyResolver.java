package com.example.orders.gateway.ratelimit;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Учитывает лимит для пользователя по {@code sub}, а для анонимного клиента — по IP.
 */
class UserOrIpKeyResolver implements KeyResolver {

    private static final String UNKNOWN_CLIENT = "ip:unknown";

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(token -> "user:" + token.getToken().getSubject())
                .switchIfEmpty(Mono.fromSupplier(() -> clientIp(exchange)));
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return UNKNOWN_CLIENT;
        }
        return "ip:" + remoteAddress.getAddress().getHostAddress();
    }
}
