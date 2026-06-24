package com.example.orders.inventory.config;

import com.example.orders.support.security.ServiceAuthorizationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * Правила доступа inventory-service в общей цепочке фильтров.
 */
@Configuration(proxyBeanMethods = false)
class InventorySecurityRules {

    @Bean
    ServiceAuthorizationCustomizer inventoryAuthorizationRules() {
        return registry -> registry
                // Каталог открыт для чтения. Изменение проходит через отдельные маршруты.
                .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                .requestMatchers("/api/inventory/**").hasRole("ADMIN");
    }
}
