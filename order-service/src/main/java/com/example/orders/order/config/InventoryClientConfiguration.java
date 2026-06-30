package com.example.orders.order.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Настройки HTTP-клиента для получения цен из inventory-service.
 */
@Configuration(proxyBeanMethods = false)
class InventoryClientConfiguration {

    /**
     * @param baseUrl адрес inventory-service
     * @param connectTimeout тайм-аут подключения
     * @param readTimeout тайм-аут чтения
     */
    @Bean
    RestClient inventoryRestClient(
            @Value("${INVENTORY_BASE_URL:http://localhost:8083}") String baseUrl,
            @Value("${INVENTORY_CONNECT_TIMEOUT_MS:1000}") long connectTimeout,
            @Value("${INVENTORY_READ_TIMEOUT_MS:2000}") long readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeout));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeout));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
