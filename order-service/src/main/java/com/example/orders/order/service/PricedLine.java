package com.example.orders.order.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Строка заказа с ценой и названием на момент запроса к каталогу.
 * @param productId идентификатор товара
 * @param sku артикул товара
 * @param productName название товара
 * @param unitPrice цена единицы
 * @param quantity количество единиц
 */
record PricedLine(
        UUID productId,
        String sku,
        String productName,
        BigDecimal unitPrice,
        int quantity) {
}
