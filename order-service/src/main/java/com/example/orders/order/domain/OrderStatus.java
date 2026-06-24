package com.example.orders.order.domain;

import java.util.Set;

/**
 * Состояния заказа в Saga. Допустимые значения закреплены ограничением {@code CHECK} в {@code V3__saga.sql}. Переходы выполняются после событий.
 * <pre>
 * PENDING ──StockRejected──────────────────────────────▶ REJECTED
 *    └─StockReserved─▶ PAYMENT_PENDING ──PaymentFailed──▶ CANCELLED
 *                            └─PaymentCompleted───────▶ CONFIRMED
 *                                  отмена клиента ────▶ CANCELLATION_PENDING
 *                                       PaymentRefunded ────▶ CANCELLED
 * </pre>
 * <p>Неожиданное для текущего состояния событие записывается в лог и не меняет состояние.
 */
public enum OrderStatus {

    /** Заказ принят, отправлен {@code OrderCreated}. Ответы склада и оплаты ещё не получены. */
    PENDING,

    /**
     * Остатки зарезервированы. В штатном сценарии {@code StockReserved} сразу переводит заказ в {@link #PAYMENT_PENDING}. Состояние поддерживается для ручной остановки между этими шагами.
     */
    STOCK_RESERVED,

    /** Остатки зарезервированы, отправлена команда {@code ProcessPayment}. */
    PAYMENT_PENDING,

    /** Товар зарезервирован, платёж прошёл, резерв подтверждён. */
    CONFIRMED,

    /**
     * Отмена подтверждённого заказа. Команды {@code RefundPayment} и {@code ReleaseStock} отправлены. После {@code PaymentRefunded} заказ станет отменённым.
     */
    CANCELLATION_PENDING,

    /** Обработка отменена из-за ошибки оплаты, запроса клиента или тайм-аута. */
    CANCELLED,

    /** Заказ отклонён до резервирования и оплаты, например при недостатке остатков. */
    REJECTED;

    private static final Set<OrderStatus> FINAL = Set.of(CONFIRMED, CANCELLED, REJECTED);

    /** Незавершённые состояния для проверки тайм-аутов Saga. */
    public static final Set<OrderStatus> UNFINISHED =
            Set.of(PENDING, STOCK_RESERVED, PAYMENT_PENDING, CANCELLATION_PENDING);

    /** Состояния, в которых возможны резерв и платёж. */
    public static final Set<OrderStatus> STOCK_HELD = Set.of(STOCK_RESERVED, PAYMENT_PENDING);

    /** @return признак конечного состояния заказа */
    public boolean isFinal() {
        return FINAL.contains(this);
    }
}
