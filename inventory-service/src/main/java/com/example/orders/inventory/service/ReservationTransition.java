package com.example.orders.inventory.service;

/** Результат {@link StockReservationService#release} или {@link StockReservationService#commit}. */
public enum ReservationTransition {

    /** Строки {@code RESERVED} перешли в новое состояние. */
    APPLIED,

    /**
     * Подходящих строк для перехода больше нет.
     */
    ALREADY_APPLIED,

    /** Записи резервирования по заказу отсутствуют. */
    UNKNOWN_ORDER
}
