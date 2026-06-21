package com.example.orders.common.events;

/**
 * Общие имена топиков Kafka.
 */
public final class Topics {

    /**
     * События order-service: {@link OrderCreated}, {@link OrderConfirmed}, {@link OrderCancelled}, {@link OrderRejected}.
     */
    public static final String ORDER_EVENTS = "order.events";

    /** События inventory-service: {@link StockReserved} и {@link StockRejected}. */
    public static final String INVENTORY_EVENTS = "inventory.events";

    /**
     * События payment-service: {@link PaymentCompleted}, {@link PaymentFailed}, {@link PaymentRefunded}.
     */
    public static final String PAYMENT_EVENTS = "payment.events";

    /**
     * Команды для inventory-service: {@link CommitStock} после оплаты и {@link ReleaseStock} при компенсации.
     */
    public static final String INVENTORY_COMMANDS = "inventory.commands";

    /**
     * Команды для payment-service: {@link ProcessPayment} и {@link RefundPayment}.
     */
    public static final String PAYMENT_COMMANDS = "payment.commands";

    /** Суффикс для топика необработанных сообщений. */
    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
    }

    /** Топик необработанных сообщений, например {@code order.events.DLT}. */
    public static String deadLetterTopic(String topic) {
        return topic + DLT_SUFFIX;
    }
}
