package com.example.orders.common.events;

import java.util.Map;
import java.util.Optional;

/**
 * Каталог типов сообщений по значению {@code eventType} в заголовке и теле.
 */
public final class EventTypes {

    private static final Map<String, Class<?>> PAYLOAD_TYPES = Map.ofEntries(
            Map.entry(OrderCreated.class.getSimpleName(), OrderCreated.class),
            Map.entry(OrderConfirmed.class.getSimpleName(), OrderConfirmed.class),
            Map.entry(OrderCancelled.class.getSimpleName(), OrderCancelled.class),
            Map.entry(OrderRejected.class.getSimpleName(), OrderRejected.class),
            Map.entry(StockReserved.class.getSimpleName(), StockReserved.class),
            Map.entry(StockRejected.class.getSimpleName(), StockRejected.class),
            Map.entry(ReleaseStock.class.getSimpleName(), ReleaseStock.class),
            Map.entry(CommitStock.class.getSimpleName(), CommitStock.class),
            Map.entry(ProcessPayment.class.getSimpleName(), ProcessPayment.class),
            Map.entry(RefundPayment.class.getSimpleName(), RefundPayment.class),
            Map.entry(PaymentCompleted.class.getSimpleName(), PaymentCompleted.class),
            Map.entry(PaymentFailed.class.getSimpleName(), PaymentFailed.class),
            Map.entry(PaymentRefunded.class.getSimpleName(), PaymentRefunded.class));

    private EventTypes() {
    }

    /**
     * @param eventType имя типа; может быть {@code null}
     * @return класс сообщения или пустое значение для неизвестного типа
     */
    public static Optional<Class<?>> payloadType(String eventType) {
        return eventType == null ? Optional.empty() : Optional.ofNullable(PAYLOAD_TYPES.get(eventType));
    }

    /** @return соответствия имён всех известных типов их классам */
    public static Map<String, Class<?>> all() {
        return PAYLOAD_TYPES;
    }
}
