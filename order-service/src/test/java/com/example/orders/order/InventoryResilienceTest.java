package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.order.client.ProductCatalogClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ответы API при недоступности inventory-service.
 */
class InventoryResilienceTest extends OrderIntegrationTest {

    @DynamicPropertySource
    static void tightenTheGuards(DynamicPropertyRegistry registry) {
        // Одна попытка на запрос упрощает подсчёт операций circuit breaker.
        registry.add("INVENTORY_RETRY_MAX_ATTEMPTS", () -> 1);
        registry.add("INVENTORY_CB_WINDOW", () -> 4);
        registry.add("INVENTORY_CB_MINIMUM_CALLS", () -> 2);
        registry.add("INVENTORY_CB_FAILURE_RATE", () -> 50);
        // Интервал исключает переход в half-open до конца теста.
        registry.add("INVENTORY_CB_OPEN_WAIT", () -> "60s");
        registry.add("INVENTORY_CONNECT_TIMEOUT_MS", () -> 300);
        registry.add("INVENTORY_READ_TIMEOUT_MS", () -> 300);
    }

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    private CircuitBreaker breaker() {
        return circuitBreakers.circuitBreaker(ProductCatalogClient.RESILIENCE_INSTANCE);
    }

    @BeforeEach
    void closeTheBreaker() {
        breaker().reset();
    }

    @Test
    void aCatalog5xxBecomes503NotAFailedOrder() throws Exception {
        catalogAnswers(id -> new MockResponse().setResponseCode(503));

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(UUID.randomUUID(), 1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(header().string("Retry-After", "5"));

        assertThat(orders.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void aCatalogThatNeverAnswersBecomes503() throws Exception {
        // Сокет принимает соединение без ответа. Запрос завершается по тайм-ауту чтения.
        catalogAnswers(id -> new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(UUID.randomUUID(), 1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));

        assertThat(orders.count()).isZero();
    }

    @Test
    void theBreakerOpensAfterTheThresholdAndThenFailsWithoutCallingTheCatalog() throws Exception {
        catalogAnswers(id -> new MockResponse().setResponseCode(500));

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/orders")
                            .header("Authorization", "Bearer " + userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody(UUID.randomUUID(), 1)))
                    .andExpect(status().isServiceUnavailable());
        }

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsBeforeTheBreakerOpened = CATALOG_CORRELATION_IDS.size();
        assertThat(callsBeforeTheBreakerOpened).isEqualTo(2);

        // Пока circuit breaker открыт, обращения к каталогу не отправляются, даже если каталог восстановился.
        catalogAnswers(id -> new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"%s\",\"sku\":\"S\",\"name\":\"N\",\"price\":1.00}".formatted(id)));

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(UUID.randomUUID(), 1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Product catalog is temporarily unavailable, please retry shortly"));

        assertThat(CATALOG_CORRELATION_IDS).hasSize(callsBeforeTheBreakerOpened);
    }

    @Test
    void anUnknownProductNeverPushesTheBreakerTowardsOpening() throws Exception {
        // Заглушка по умолчанию возвращает 404 для неизвестного идентификатора.
        for (int attempt = 0; attempt < 4; attempt++) {
            mvc.perform(post("/api/orders")
                            .header("Authorization", "Bearer " + userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody(UUID.randomUUID(), 1)))
                    .andExpect(status().isBadRequest());
        }

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }
}
