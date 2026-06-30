package com.example.orders.support.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * Отметка об обработке события конкретным потребителем в таблице {@code processed_events}.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @EmbeddedId
    private Key key;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private OffsetDateTime processedAt;

    protected ProcessedEvent() {
        // Конструктор для JPA.
    }

    private ProcessedEvent(Key key) {
        this.key = key;
        this.processedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * @param eventId идентификатор из оболочки события
     * @param consumer логическое имя потребителя
     */
    public static ProcessedEvent of(UUID eventId, String consumer) {
        return new ProcessedEvent(new Key(eventId, consumer));
    }

    public Key getKey() {
        return key;
    }

    public UUID getEventId() {
        return key == null ? null : key.getEventId();
    }

    public String getConsumer() {
        return key == null ? null : key.getConsumer();
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }

    /** Составной ключ даёт одну запись на событие и потребителя. */
    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "event_id", nullable = false, updatable = false)
        private UUID eventId;

        @Column(name = "consumer", nullable = false, length = 100, updatable = false)
        private String consumer;

        protected Key() {
            // Конструктор для JPA.
        }

        /**
         * @param eventId идентификатор события
         * @param consumer логическое имя потребителя
         */
        public Key(UUID eventId, String consumer) {
            this.eventId = eventId;
            this.consumer = consumer;
        }

        public UUID getEventId() {
            return eventId;
        }

        public String getConsumer() {
            return consumer;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(eventId, key.eventId) && Objects.equals(consumer, key.consumer);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, consumer);
        }

        @Override
        public String toString() {
            return consumer + "/" + eventId;
        }
    }
}
