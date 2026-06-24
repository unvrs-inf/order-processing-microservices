package com.example.orders.order.domain;

/**
 * Состояние возврата платежа, независимое от статуса заказа.
 */
public enum RefundStatus {

    /** Saga не ожидает возврата. */
    NONE,

    /** Команда {@code RefundPayment} отправлена. Ответ ещё не получен. */
    REQUESTED,

    /** Возврат отслеживаемого платежа подтверждён. */
    REFUNDED
}
