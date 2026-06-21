package com.example.orders.support.outbox;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.support.correlation.CorrelationId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Записывает событие в outbox в текущей транзакции. {@code Propagation.MANDATORY} защищает от записи вне транзакции изменения состояния.
 * <p>Идентификатор корреляции берётся из MDC, который заполняют HTTP-фильтр и перехватчик Kafka.
 */
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final String defaultAggregateType;

    /**
     * @param outbox репозиторий очереди
     * @param json JSON-маппер приложения
     * @param defaultAggregateType значение {@code aggregate_type} для методов с типом по умолчанию
     */
    public OutboxWriter(OutboxRepository outbox, ObjectMapper json, String defaultAggregateType) {
        this.outbox = outbox;
        this.json = json;
        this.defaultAggregateType = defaultAggregateType;
    }

    /**
     * @param aggregateId идентификатор агрегата и ключ сообщения Kafka
     * @param eventType имя типа, например {@code OrderCreated}
     * @param topic целевой топик
     * @param payload тело из {@code common-events}
     * @return неопубликованная строка, чей идентификатор служит {@code eventId}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(UUID aggregateId, String eventType, String topic, Object payload) {
        return record(defaultAggregateType, aggregateId, eventType, topic, payload);
    }

    /**
     * @param aggregateType тип агрегата
     * @param aggregateId идентификатор агрегата и ключ сообщения Kafka
     * @param eventType имя типа
     * @param topic целевой топик
     * @param payload тело из {@code common-events}
     * @return сохранённая неопубликованная строка
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(
            String aggregateType, UUID aggregateId, String eventType, String topic, Object payload) {
        // Идентификатор строки одновременно является ключом дедупликации события.
        UUID eventId = UUID.randomUUID();
        String correlationId = CorrelationId.sanitise(MDC.get(CorrelationId.MDC_KEY));
        EventEnvelope<Object> envelope =
                new EventEnvelope<>(eventId, eventType, Instant.now(), correlationId, payload);
        OutboxEvent event = OutboxEvent.pending(
                eventId, aggregateType, aggregateId, eventType, topic, serialise(envelope), correlationId);
        event.setTraceparent(currentTraceparent());
        return outbox.save(event);
    }

    /**
     * Контекст Micrometer сохраняется сейчас, поскольку публикация произойдёт в другом потоке. Он уйдёт в заголовке W3C traceparent.
     */
    static String currentTraceparent() {
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");
        if (traceId == null || spanId == null
                || !traceId.matches("[0-9a-f]{32}") || !spanId.matches("[0-9a-f]{16}")) {
            return null;
        }
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private String serialise(EventEnvelope<Object> envelope) {
        try {
            return json.writeValueAsString(envelope);
        }
        catch (JsonProcessingException e) {
            // Ошибка сериализации откатывает транзакцию, чтобы изменение состояния не осталось без события.
            throw new IllegalStateException("Could not serialise event " + envelope.eventType(), e);
        }
    }
}
