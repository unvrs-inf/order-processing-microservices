package com.example.orders.inventory.service;

import java.util.List;
import java.util.UUID;

/**
 * Результат {@link StockReservationService#reserve}. Потребитель Kafka преобразует его в ответ Saga.
 * @param orderId заказ
 * @param outcome результат резервирования
 * @param shortages дефицит по строкам
 */
public record ReservationResult(UUID orderId, Outcome outcome, List<Shortage> shortages) {

    /** Возможные результаты резервирования. */
    public enum Outcome {

        /** Этот вызов создал резерв для всех строк. */
        RESERVED,

        /** Резерв для заказа уже существует. */
        ALREADY_RESERVED,

        /** Резерв не создан, поскольку хотя бы одной строки не хватает. */
        REJECTED,

        /**
         * Резерв не создан. Для заказа сохранён запрос на освобождение.
         */
        ALREADY_RELEASED
    }

    /** Причина отсутствия резерва для одной строки. */
    public enum Reason {

        /** Товар с таким идентификатором не найден. */
        PRODUCT_NOT_FOUND,

        /** Товар найден, но доступного количества недостаточно. */
        INSUFFICIENT_STOCK
    }

    /**
     * @param productId товар, для которого возник дефицит
     * @param requested запрошенное количество
     * @param available доступное количество
     * @param reason причина дефицита
     * @param sku артикул товара
     */
    public record Shortage(UUID productId, String sku, Reason reason, int requested, int available) {
    }

    /**
     * @param orderId заказ, для которого создан резерв
     * @return успешный результат
     */
    public static ReservationResult reserved(UUID orderId) {
        return new ReservationResult(orderId, Outcome.RESERVED, List.of());
    }

    /**
     * @param orderId заказ с уже существующим резервом
     * @return результат повторного резервирования
     */
    public static ReservationResult alreadyReserved(UUID orderId) {
        return new ReservationResult(orderId, Outcome.ALREADY_RESERVED, List.of());
    }

    /**
     * @param orderId заказ с записью об освобождении
     * @return результат отказа из-за освобождения
     */
    public static ReservationResult alreadyReleased(UUID orderId) {
        return new ReservationResult(orderId, Outcome.ALREADY_RELEASED, List.of());
    }

    /**
     * @param orderId заказ, для которого резерв не создан
     * @param shortages причины отказа по строкам
     * @return отклонённый результат
     */
    public static ReservationResult rejected(UUID orderId, List<Shortage> shortages) {
        return new ReservationResult(orderId, Outcome.REJECTED, List.copyOf(shortages));
    }

    /**
     * @return остаётся ли у заказа резерв после вызова
     */
    public boolean isHoldingStock() {
        return outcome == Outcome.RESERVED || outcome == Outcome.ALREADY_RESERVED;
    }
}
