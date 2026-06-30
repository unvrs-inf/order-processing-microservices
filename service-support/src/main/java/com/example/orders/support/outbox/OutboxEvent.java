package com.example.orders.support.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Событие для публикации в Kafka, сопоставленное с {@code outbox_events}.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    /** Тип {@code aggregate_type} по умолчанию. */
    public static final String DEFAULT_AGGREGATE_TYPE = "Aggregate";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    /** Ключ сообщения Kafka. Он помещает события одного заказа в одну партицию. */
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 200, updatable = false)
    private String topic;

    /** Сериализованная {@code EventEnvelope}. Публикация отправит эти данные как есть. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    /** Контекст W3C из транзакции, создавшей событие. */
    @Column(name = "traceparent", length = 55, updatable = false)
    private String traceparent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {
        // Конструктор для JPA.
    }

    private OutboxEvent(UUID eventId, String aggregateType, UUID aggregateId, String eventType, String topic,
            String payload, String correlationId) {
        this.id = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.correlationId = correlationId;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.attempts = 0;
    }

    /**
     * @param eventId идентификатор события, совпадающий со значением внутри {@code payload}
     * @param aggregateType имя типа агрегата
     * @param aggregateId идентификатор агрегата и ключ Kafka
     * @param eventType имя типа события
     * @param topic имя топика Kafka
     * @param payload тело сообщения
     * @param correlationId идентификатор корреляции
     */
    public static OutboxEvent pending(UUID eventId, String aggregateType, UUID aggregateId, String eventType,
            String topic, String payload, String correlationId) {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, topic, payload, correlationId);
    }

    /** Отмечает публикацию после подтверждения брокером. */
    public void markPublished() {
        this.publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.lastError = null;
    }

    /**
     * Записывает ошибку. Следующий опрос вернётся к неопубликованной строке.
     * @param error текст HTTP-статуса
     */
    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPayload() {
        return payload;
    }

    public String getTraceparent() {
        return traceparent;
    }

    /** @param traceparent значение W3C {@code traceparent}, может быть {@code null} */
    public void setTraceparent(String traceparent) {
        this.traceparent = traceparent;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
