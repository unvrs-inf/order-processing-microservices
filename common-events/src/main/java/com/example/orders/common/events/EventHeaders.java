package com.example.orders.common.events;

/**
 * Общие заголовки событий Kafka.
 */
public final class EventHeaders {

    /** Уникальный идентификатор для дедупликации. */
    public static final String EVENT_ID = "eventId";

    /** Имя типа тела, например {@code OrderCreated}. */
    public static final String EVENT_TYPE = "eventType";

    /** Идентификатор корреляции запроса, вызвавшего событие. */
    public static final String CORRELATION_ID = "correlationId";

    private EventHeaders() {
    }
}
