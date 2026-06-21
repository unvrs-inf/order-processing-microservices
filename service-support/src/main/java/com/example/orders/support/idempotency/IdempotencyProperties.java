package com.example.orders.support.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Настройки дедупликации с префиксом {@code idempotency.*}. */
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    /** Включает журнал обработанных событий для сервиса. */
    private boolean enabled;

    /**
     * Срок хранения отметки должен покрывать период повторной доставки.
     */
    private Duration retention = Duration.ofDays(7);

    /** Интервал запуска очистки. */
    private long cleanupIntervalMs = 3_600_000;

    /** Отключается в тестах, которым нужны данные без фоновых изменений. */
    private boolean schedulerEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
}
