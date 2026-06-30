package com.example.orders.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Оболочка сообщения Kafka, общая для сервисов.
 * @param <T> тип элемента
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String correlationId,
        T payload) {

    /** Создаёт оболочку с новым идентификатором события и текущим временем. */
    public static <T> EventEnvelope<T> of(String eventType, String correlationId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, Instant.now(), correlationId, payload);
    }

    /** Создаёт оболочку, используя простое имя класса тела в качестве типа события. */
    public static <T> EventEnvelope<T> of(String correlationId, T payload) {
        return of(payload.getClass().getSimpleName(), correlationId, payload);
    }
}
