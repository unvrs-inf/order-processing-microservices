package com.example.orders.inventory.service;

/**
 * Результат сверки одного заказа или причина отказа.
 */
public enum ReconciliationOutcome {

    /** Строки {@code RESERVED} освобождены. */
    RELEASED,

    /** Все строки уже освобождены, повторный вызов оставил состояние прежним. */
    ALREADY_RELEASED,

    /** Резерв не найден. Отметка об освобождении сохранена. */
    NO_RESERVATIONS,

    /**
     * Есть строки {@code COMMITTED}. Возврат продажи выполняется штатной компенсацией.
     */
    SKIPPED_COMMITTED,

    /**
     * Проверенный статус не входит в {@code CANCELLED} или {@code REJECTED}.
     */
    REFUSED_ACTIVE_ORDER,

    /** order-service вернул {@code 404}. Изменений нет. */
    REFUSED_ORDER_NOT_FOUND,

    /** order-service вернул {@code 401} или {@code 403}. Изменений нет. */
    REFUSED_ACCESS_DENIED,

    /**
     * Получен некорректный ответ или ответ по другому заказу.
     */
    REFUSED_UNVERIFIABLE,

    /** order-service недоступен, истёк тайм-аут или получен {@code 5xx}. */
    REFUSED_VERIFICATION_UNAVAILABLE,

    /**
     * Заказ пропущен после серии ошибок проверки в этой пачке.
     */
    SKIPPED_VERIFICATION_UNAVAILABLE
}
