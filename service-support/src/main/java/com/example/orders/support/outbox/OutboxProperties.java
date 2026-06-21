package com.example.orders.support.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки общей реализации Transactional Outbox с префиксом {@code outbox.*}.
 */
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

    /** Включает outbox для сервиса. */
    private boolean enabled;

    /** Тип {@code aggregate_type} для вызова {@code OutboxWriter.record(...)} без явного типа. */
    private String aggregateType = OutboxEvent.DEFAULT_AGGREGATE_TYPE;

    /**
     * Интервал между опросами определяет задержку публикации.
     */
    private long pollIntervalMs = 500;

    /**
     * Число строк в пачке {@code FOR UPDATE SKIP LOCKED}. Блокировки держатся до конца пачки.
     */
    private int batchSize = 50;

    /**
     * Время ожидания одной отправки больше {@code producer.deliveryTimeoutMs}.
     */
    private Duration sendTimeout = Duration.ofSeconds(15);

    /**
     * Срок хранения опубликованных строк для аудита.
     */
    private Duration retention = Duration.ofDays(7);

    /** Интервал удаления устаревших записей. */
    private long cleanupIntervalMs = 3_600_000;

    /** Отключается в тестах с ручным вызовом публикации. */
    private boolean schedulerEnabled = true;

    private final Producer producer = new Producer();

    /** Тайм-ауты продюсера outbox. */
    public static class Producer {

        /** Максимальное ожидание метаданных в {@code send()}. */
        private int maxBlockMs = 5000;

        /** Тайм-аут одного запроса к брокеру. */
        private int requestTimeoutMs = 5000;

        /** Общий срок публикации с учётом внутренних повторов клиента. */
        private int deliveryTimeoutMs = 10000;

        public int getMaxBlockMs() {
            return maxBlockMs;
        }

        public void setMaxBlockMs(int maxBlockMs) {
            this.maxBlockMs = maxBlockMs;
        }

        public int getRequestTimeoutMs() {
            return requestTimeoutMs;
        }

        public void setRequestTimeoutMs(int requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
        }

        public int getDeliveryTimeoutMs() {
            return deliveryTimeoutMs;
        }

        public void setDeliveryTimeoutMs(int deliveryTimeoutMs) {
            this.deliveryTimeoutMs = deliveryTimeoutMs;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public Producer getProducer() {
        return producer;
    }
}
