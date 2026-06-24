package com.example.orders.inventory.api;

import com.example.orders.inventory.domain.Product;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Представление для администратора: поля каталога, резерв и другие складские данные.
 * @param id идентификатор записи
 * @param sku артикул товара
 * @param name название
 * @param description описание
 * @param price цена
 * @param availableQuantity доступный остаток
 * @param reservedQuantity количество единиц в резерве
 * @param version версия записи для оптимистической блокировки
 * @param createdAt время создания
 * @param updatedAt время последнего изменения
 */
public record ProductAdminResponse(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        int availableQuantity,
        int reservedQuantity,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param product сохранённый товар
     * @return представление товара для администратора
     */
    public static ProductAdminResponse of(Product product) {
        return new ProductAdminResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getAvailableQuantity(),
                product.getReservedQuantity(),
                product.getVersion(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
