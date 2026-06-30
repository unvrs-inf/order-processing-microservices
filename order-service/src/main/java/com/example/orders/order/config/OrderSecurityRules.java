package com.example.orders.order.config;

import com.example.orders.support.security.ServiceAuthorizationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * Правила order-service в общей цепочке фильтров.
 */
@Configuration(proxyBeanMethods = false)
class OrderSecurityRules {

    @Bean
    ServiceAuthorizationCustomizer orderAuthorizationRules() {
        return registry -> registry
                .requestMatchers(HttpMethod.GET, "/api/orders").hasRole("ADMIN");
    }
}
