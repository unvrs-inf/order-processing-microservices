package com.example.orders.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Поля ответа {@code GET /api/products/{id}}, необходимые для оформления заказа.
 * @param id идентификатор товара
 * @param sku артикул
 * @param name название
 * @param price цена
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogProduct(
        UUID id,
        String sku,
        String name,
        BigDecimal price) {
}
