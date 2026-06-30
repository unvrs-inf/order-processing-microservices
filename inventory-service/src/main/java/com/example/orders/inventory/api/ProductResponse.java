package com.example.orders.inventory.api;

import com.example.orders.inventory.domain.Product;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Публичные поля товара для просмотра каталога и создания заказа.
 * @param id идентификатор записи
 * @param sku артикул товара
 * @param name название
 * @param description описание
 * @param price цена
 * @param availableQuantity доступный остаток
 */
public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        int availableQuantity) {

    /**
     * @param product сохранённый товар
     * @return публичная карточка товара
     */
    public static ProductResponse of(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getAvailableQuantity());
    }
}
