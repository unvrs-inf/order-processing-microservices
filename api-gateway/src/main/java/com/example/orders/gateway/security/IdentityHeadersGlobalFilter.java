package com.example.orders.gateway.security;

import java.util.stream.Collectors;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Передаёт сервису данные проверенного пользователя и удаляет клиентские заголовки с идентификатором и ролями.
 */
@Component
class IdentityHeadersGlobalFilter implements GlobalFilter, Ordered {

    /** Выполняется до фильтра маршрутизации, который копирует заголовки в запрос к сервису. */
    static final int ORDER = 0;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .filter(Authentication::isAuthenticated)
                .map(token -> withIdentity(exchange, token))
                .defaultIfEmpty(withoutIdentity(exchange))
                .flatMap(chain::filter);
    }

    private static ServerWebExchange withoutIdentity(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(GatewayHeaders.USER_ID);
                    headers.remove(GatewayHeaders.USER_ROLES);
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private static ServerWebExchange withIdentity(ServerWebExchange exchange, JwtAuthenticationToken token) {
        String roles = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)
                .collect(Collectors.joining(","));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(GatewayHeaders.USER_ID, token.getToken().getSubject());
                    headers.set(GatewayHeaders.USER_ROLES, roles);
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
