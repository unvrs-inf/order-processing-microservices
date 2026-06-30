package com.example.orders.order.service;

import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.Topics;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.support.outbox.OutboxWriter;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Записывает заказ, строки и событие {@code OrderCreated} в одной транзакции.
 */
@Component
class OrderTransactions {

    private final OrderRepository orders;
    private final OutboxWriter outbox;

    OrderTransactions(OrderRepository orders, OutboxWriter outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    /**
     * @param userId владелец заказа
     * @param idempotencyKey необязательный ключ идемпотентности
     * @param currency код валюты ISO 4217
     * @param lines строки заказа
     */
    @Transactional
    Order placeOrder(UUID userId, String idempotencyKey, String currency, List<PricedLine> lines) {
        Order order = Order.pending(userId, currency, idempotencyKey);
        for (PricedLine line : lines) {
            order.addLine(line.productId(), line.productName(), line.unitPrice(), line.quantity());
        }
        // Проверяем ограничения базы до публикации события в outbox.
        Order saved = orders.saveAndFlush(order);

        outbox.record(
                saved.getId(),
                OrderCreated.class.getSimpleName(),
                Topics.ORDER_EVENTS,
                toEvent(saved, lines));
        return saved;
    }

    private static OrderCreated toEvent(Order order, List<PricedLine> lines) {
        List<com.example.orders.common.events.OrderItem> items = lines.stream()
                .map(line -> new com.example.orders.common.events.OrderItem(
                        line.productId(), line.sku(), line.quantity(), line.unitPrice()))
                .toList();
        return new OrderCreated(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                order.getTotalAmount(),
                order.getCurrency(),
                items);
    }
}
