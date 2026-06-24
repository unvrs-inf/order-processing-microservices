package com.example.orders.order.service;

import com.example.orders.order.api.OrderResponse;

/**
 * Результат {@code POST /api/orders}: заказ и признак его создания этим запросом.
 * @param order сохранённый заказ
 * @param created признак создания заказа этим запросом
 */
public record OrderPlacement(OrderResponse order, boolean created) {
}
