package com.example.orders.order.api;

import com.example.orders.support.outbox.OutboxEvent;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * Метаданные события или команды, которую order-service отправил по заказу.
 * @param eventType имя типа события
 * @param topic имя топика Kafka
 * @param createdAt время создания
 * @param publishedAt время публикации
 * @param attempts число попыток публикации
 * @param traceId идентификатор трейса
 * @param correlationId идентификатор корреляции
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderEventResponse(
        String eventType,
        String topic,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt,
        int attempts,
        String traceId,
        String correlationId) {

    /** W3C {@code traceparent} версии 00 содержит ровно 55 символов, включая разделители. */
    private static final String TRACEPARENT_PATTERN =
            "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";
    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ZERO_PARENT_ID = "0000000000000000";

    /**
     * @param event сохранённая строка outbox
     * @return метаданные для API
     */
    public static OrderEventResponse of(OutboxEvent event) {
        return new OrderEventResponse(
                event.getEventType(),
                event.getTopic(),
                event.getCreatedAt(),
                event.getPublishedAt(),
                event.getAttempts(),
                traceIdOf(event.getTraceparent()),
                event.getCorrelationId());
    }

    /**
     * @param traceparent значение W3C {@code traceparent}, может быть {@code null}
     * @return идентификатор трейса из корректного заголовка
     */
    static String traceIdOf(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        if (traceparent.length() != 55 || !traceparent.matches(TRACEPARENT_PATTERN)) {
            return null;
        }
        String traceId = traceparent.substring(3, 35);
        String parentId = traceparent.substring(36, 52);
        // Идентификатор трейса или родительского спана из одних нулей недопустим.
        return ZERO_TRACE_ID.equals(traceId) || ZERO_PARENT_ID.equals(parentId) ? null : traceId;
    }
}
