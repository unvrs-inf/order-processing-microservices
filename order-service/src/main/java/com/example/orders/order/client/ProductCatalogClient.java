package com.example.orders.order.client;

import com.example.orders.support.correlation.CorrelationId;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP-клиент склада для получения цены на момент оформления заказа.
 */
@Component
public class ProductCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(ProductCatalogClient.class);

    /** Имя настроек повторов и circuit breaker Resilience4j. */
    public static final String RESILIENCE_INSTANCE = "inventory";

    private final RestClient restClient;

    ProductCatalogClient(RestClient inventoryRestClient) {
        this.restClient = inventoryRestClient;
    }

    /**
     * @param productId товар для получения цены
     * @return снимок товара из каталога
     * @throws UnknownProductException если каталог не нашёл товар
     * @throws InventoryCallFailedException если запрос каталога завершился сбоем
     * @throws InventoryUnavailableException если получение цены временно недоступно
     */
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "catalogIsShutOff")
    @Retry(name = RESILIENCE_INSTANCE)
    public CatalogProduct fetch(UUID productId) {
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        try {
            CatalogProduct product = restClient.get()
                    .uri("/api/products/{id}", productId)
                    .header(CorrelationId.HEADER, CorrelationId.sanitise(correlationId))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, response) -> {
                        throw new UnknownProductException(productId);
                    })
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new InventoryCallFailedException(
                                "Product catalog answered " + response.getStatusCode().value());
                    })
                    .body(CatalogProduct.class);
            if (product == null || product.price() == null) {
                throw new InventoryCallFailedException("Product catalog returned an empty body for " + productId);
            }
            return product;
        }
        catch (ResourceAccessException e) {
            // Тайм-аут подключения или чтения, отказ соединения или сброс сокета.
            throw new InventoryCallFailedException("Product catalog is not reachable", e);
        }
        catch (RestClientException e) {
            // Некорректное тело или цикл перенаправлений также означают сбой зависимости.
            throw new InventoryCallFailedException("Product catalog call failed", e);
        }
    }

    /**
     * Резервный обработчик для открытого circuit breaker.
     * @param productId идентификатор товара
     * @param ex исходное исключение
     */
    CatalogProduct catalogIsShutOff(UUID productId, CallNotPermittedException ex) {
        log.warn("Circuit breaker '{}' is open, not pricing product {}", RESILIENCE_INSTANCE, productId);
        throw new InventoryUnavailableException(
                "Product catalog is temporarily unavailable, please retry shortly");
    }
}
