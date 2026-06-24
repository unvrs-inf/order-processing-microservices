package com.example.orders.inventory.client;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP-клиент для проверки статуса заказа перед сверкой резервов.
 */
@Configuration(proxyBeanMethods = false)
class OrderClientConfiguration {

    /**
     * @param baseUrl адрес order-service из {@code ORDER_BASE_URL}
     * @param connectTimeout тайм-аут подключения
     * @param readTimeout тайм-аут чтения
     */
    @Bean
    RestClient orderRestClient(
            @Value("${ORDER_BASE_URL:http://localhost:8082}") String baseUrl,
            @Value("${ORDER_CONNECT_TIMEOUT_MS:1000}") long connectTimeout,
            @Value("${ORDER_READ_TIMEOUT_MS:2000}") long readTimeout) {
        return create(baseUrl, connectTimeout, readTimeout);
    }

    static RestClient create(String baseUrl, long connectTimeoutMs, long readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
