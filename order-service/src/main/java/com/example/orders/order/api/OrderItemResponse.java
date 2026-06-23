package com.example.orders.order.api;

import com.example.orders.order.domain.OrderLine;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Строка заказа с ценой на момент оформления.
 * @param productId идентификатор товара
 * @param productName название товара
 * @param unitPrice цена единицы
 * @param quantity количество единиц
 * @param lineTotal стоимость строки заказа
 */
public record OrderItemResponse(
        UUID productId,
        String productName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal) {

    /**
     * @param line сохранённая строка заказа
     * @return строка для ответа API
     */
    public static OrderItemResponse of(OrderLine line) {
        return new OrderItemResponse(
                line.getProductId(),
                line.getProductName(),
                line.getUnitPrice(),
                line.getQuantity(),
                line.getLineTotal());
    }
}
