package com.example.orders.inventory.domain;

/** Состояния строки резерва, закреплённые ограничением CHECK. */
public enum ReservationStatus {

    /** Единицы перемещены из {@code available} в резерв заказа. */
    RESERVED,

    /** Резерв освобождён, единицы снова доступны. */
    RELEASED,

    /** Продажа подтверждена, единицы списаны из резерва. */
    COMMITTED
}
