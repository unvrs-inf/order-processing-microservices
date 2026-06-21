package com.example.orders.support.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает Transactional Outbox при {@code outbox.enabled=true}. Таблица {@code outbox_events} создаётся Flyway в базе сервиса. Сущность и репозиторий подключаются в сервисе через {@code @EntityScan} и {@code @EnableJpaRepositories}.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({KafkaTemplate.class, EntityManagerFactory.class})
@ConditionalOnProperty(name = "outbox.enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    /**
     * Продюсер фоновой публикации.
     * <p>Отправляет сохранённую строку из {@code outbox_events.payload}. {@code acks=all} ждёт подтверждения синхронных реплик. {@code enable.idempotence=true} устраняет дубликаты внутренних повторов клиента.
     * <p>Короткие тайм-ауты ограничивают длительность блокировки строк во время сбоя брокера.
     * @param properties настройки {@code spring.kafka.*}, включая адреса брокеров
     * @param sslBundles настройки TLS, если заданы
     * @param outbox параметры outbox
     * @return строковый продюсер с доставкой как минимум один раз
     */
    @Bean
    @ConditionalOnMissingBean(name = "outboxKafkaTemplate")
    public KafkaTemplate<String, String> outboxKafkaTemplate(
            KafkaProperties properties, ObjectProvider<SslBundles> sslBundles, OutboxProperties outbox) {
        Map<String, Object> config = new HashMap<>(
                properties.buildProducerProperties(sslBundles.getIfAvailable()));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, outbox.getProducer().getMaxBlockMs());
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, outbox.getProducer().getRequestTimeoutMs());
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, outbox.getProducer().getDeliveryTimeoutMs());
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    /**
     * @param repository репозиторий outbox
     * @param json JSON-маппер приложения
     * @param outbox параметры outbox
     * @return обработчик записи в текущей транзакции
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(
            OutboxRepository repository, ObjectMapper json, OutboxProperties outbox) {
        return new OutboxWriter(repository, json, outbox.getAggregateType());
    }

    /**
     * @param repository репозиторий outbox
     * @param outboxKafkaTemplate продюсер отправки
     * @param outbox параметры outbox
     * @return обработчик публикации для задач и тестов
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxRelay outboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, String> outboxKafkaTemplate,
            OutboxProperties outbox) {
        return new OutboxRelay(
                repository,
                outboxKafkaTemplate,
                outbox.getBatchSize(),
                outbox.getSendTimeout(),
                outbox.getRetention());
    }

    /** Планировщик можно отключить в тестах, которые запускают публикацию вручную. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "outbox.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    public static class SchedulerConfiguration {

        /**
         * @param relay обработчик публикации
         * @return задача периодического опроса
         */
        @Bean
        @ConditionalOnMissingBean
        public OutboxRelayScheduler outboxRelayScheduler(OutboxRelay relay) {
            return new OutboxRelayScheduler(relay);
        }
    }
}
