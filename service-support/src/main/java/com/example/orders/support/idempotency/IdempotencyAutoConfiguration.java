package com.example.orders.support.idempotency;

import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Подключает дедупликацию при {@code idempotency.enabled=true}. Таблица создаётся миграцией Flyway.
 */
@AutoConfiguration
@ConditionalOnClass(EntityManagerFactory.class)
@ConditionalOnProperty(name = "idempotency.enabled", havingValue = "true")
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    /**
     * @param repository репозиторий {@code processed_events}
     */
    @Bean
    @ConditionalOnMissingBean
    public ProcessedEvents processedEvents(ProcessedEventRepository repository) {
        return new ProcessedEvents(repository);
    }

    /** Планировщик удаляет записи старше периода хранения. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "idempotency.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    public static class CleanupConfiguration {

        /**
         * @param processedEvents компонент дедупликации
         * @param properties параметры конфигурации
         */
        @Bean
        @ConditionalOnMissingBean
        public ProcessedEventCleanup processedEventCleanup(
                ProcessedEvents processedEvents, IdempotencyProperties properties) {
            return new ProcessedEventCleanup(processedEvents, properties.getRetention());
        }
    }

    /** Удаляет отметки, которые больше не нужны для повторных доставок. */
    public static class ProcessedEventCleanup {

        private final ProcessedEvents processedEvents;
        private final Duration retention;

        ProcessedEventCleanup(ProcessedEvents processedEvents, Duration retention) {
            this.processedEvents = processedEvents;
            this.retention = retention;
        }

        @Scheduled(
                fixedDelayString = "${idempotency.cleanup-interval-ms:3600000}",
                initialDelayString = "${idempotency.cleanup-interval-ms:3600000}")
        void purge() {
            processedEvents.purgeOlderThan(retention);
        }
    }
}
