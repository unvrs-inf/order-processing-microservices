package com.example.orders.support.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Настройки повторной доставки и DLT с префиксом {@code messaging.consumer.*}. */
@ConfigurationProperties(prefix = "messaging.consumer")
public class EventConsumerProperties {

    /** Включает потребителей Kafka. */
    private boolean enabled;

    /**
     * Число потоков в контейнере. По умолчанию один, порядок заказов обеспечивается ключом партиции.
     */
    private int concurrency = 1;

    /**
     * Число повторных попыток до отправки в DLT.
     */
    private int retries = 3;

    /** Задержка до первой повторной попытки. */
    private long initialBackoffMs = 500;

    /** Множитель задержки после неудачной попытки. */
    private double backoffMultiplier = 2.0;

    /** Максимальная задержка, ограничивающая простой партиции. */
    private long maxBackoffMs = 4000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
        this.initialBackoffMs = initialBackoffMs;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
        this.maxBackoffMs = maxBackoffMs;
    }
}
