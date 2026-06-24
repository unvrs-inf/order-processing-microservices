package com.example.orders.inventory.client;

/**
 * Результат проверки order-service, от которого зависит возможность освобождения.
 */
public enum OrderStatusVerdict {

    /** Заказ подтверждён как {@code CANCELLED}. */
    CANCELLED,

    /** Заказ подтверждён как {@code REJECTED}. */
    REJECTED,

    /**
     * Статус заказа не разрешает ручное освобождение.
     */
    ACTIVE,

    /** order-service вернул {@code 404}. Заказ отсутствует или недоступен пользователю. */
    NOT_FOUND,

    /** order-service отклонил токен администратора с {@code 401} или {@code 403}. */
    ACCESS_DENIED,

    /**
     * Ответ непригоден для проверки: неверное тело или идентификатор заказа.
     */
    UNVERIFIABLE,

    /** Сетевой сбой, тайм-аут, {@code 5xx}, {@code 408} или {@code 429}. */
    UNAVAILABLE;

    /** @return разрешает ли проверенный статус освободить резерв */
    public boolean permitsRelease() {
        return this == CANCELLED || this == REJECTED;
    }

    /**
     * @return указывает ли ответ на сбой зависимости, учётных данных или контракта
     */
    public boolean isDependencyFailure() {
        return this == ACCESS_DENIED || this == UNVERIFIABLE || this == UNAVAILABLE;
    }
}
