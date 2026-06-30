package com.example.orders.support.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Позволяет сервису добавить свои правила к общей цепочке авторизации.
 */
@FunctionalInterface
public interface ServiceAuthorizationCustomizer {

    /**
     * @param registry конфигуратор для добавления {@code requestMatchers(...)}
     */
    void customize(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry);
}
