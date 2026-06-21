package com.example.orders.support.messaging;

import com.example.orders.common.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.StringOrBytesSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Подключает обработчики при {@code messaging.consumer.enabled=true}.
 * <p>Сообщения читаются строками. {@link EventEnvelopeReader} классифицирует ошибки формата на уровне приложения.
 * <p>Обрабатываемые ошибки повторяются с экспоненциальной задержкой. После исчерпания попыток запись с деталями ошибки уходит в {@code <topic>.DLT}. {@link PoisonEventException} направляется туда сразу.
 * <p>{@code AckMode.RECORD} фиксирует смещение после обработки или отправки в DLT. Повтор доставки поглощает {@code processed_events}.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({KafkaTemplate.class, ConcurrentKafkaListenerContainerFactory.class})
@ConditionalOnProperty(name = "messaging.consumer.enabled", havingValue = "true")
@EnableConfigurationProperties(EventConsumerProperties.class)
@EnableKafka
public class EventConsumerAutoConfiguration {

    /** Имя фабрики контейнеров для {@code @KafkaListener}. */
    public static final String CONTAINER_FACTORY = "eventListenerContainerFactory";

    /**
     * @param json JSON-маппер приложения
     * @return общий декодер оболочек событий
     */
    @Bean
    @ConditionalOnMissingBean
    public EventEnvelopeReader eventEnvelopeReader(ObjectMapper json) {
        return new EventEnvelopeReader(json);
    }

    /**
     * Продюсер DLT с {@code acks=all} и идемпотентной отправкой.
     * @param properties настройки {@code spring.kafka.*}
     * @param sslBundles настройки TLS, если заданы
     * @return продюсер строк и необработанных байтов
     */
    @Bean
    @ConditionalOnMissingBean(name = "deadLetterKafkaTemplate")
    public KafkaTemplate<Object, Object> deadLetterKafkaTemplate(
            KafkaProperties properties, ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry) {
        Map<String, Object> config = new HashMap<>(
                properties.buildProducerProperties(sslBundles.getIfAvailable()));
        // StringOrBytes сохраняет исходное представление сообщения для отправки в DLT.
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringOrBytesSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringOrBytesSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    /**
     * @param deadLetterKafkaTemplate продюсер для {@code <topic>.DLT}
     * @param properties параметры повторных попыток
     * @return общий обработчик повторов и отправки в DLT
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler eventErrorHandler(
            KafkaTemplate<Object, Object> deadLetterKafkaTemplate, EventConsumerProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                // Партицию выбирает продюсер. Число партиций DLT может отличаться от исходного топика.
                (record, exception) -> new TopicPartition(Topics.deadLetterTopic(record.topic()), -1));

        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(properties.getInitialBackoffMs());
        backOff.setMultiplier(properties.getBackoffMultiplier());
        backOff.setMaxInterval(properties.getMaxBackoffMs());
        // Ограничиваем число доставок. DEFAULT_MAX_ELAPSED_TIME удерживал бы запись в повторах дольше.
        backOff.setMaxAttempts(Math.max(0, properties.getRetries()));
        backOff.setMaxElapsedTime(Long.MAX_VALUE);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Ошибки этого типа сразу направляются в DLT. Ошибки десериализации и преобразования уже есть в списке DefaultErrorHandler.
        handler.addNotRetryableExceptions(PoisonEventException.class);
        return handler;
    }

    /**
     * @param properties настройки {@code spring.kafka.*}
     * @param sslBundles настройки TLS, если заданы
     * @return фабрика потребителей строковых ключей и значений
     */
    @Bean
    @ConditionalOnMissingBean(name = "eventConsumerFactory")
    public ConsumerFactory<String, String> eventConsumerFactory(
            KafkaProperties properties, ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry) {
        Map<String, Object> config = new HashMap<>(
                properties.buildConsumerProperties(sslBundles.getIfAvailable()));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.remove("spring.deserializer.value.delegate.class");
        config.remove("spring.deserializer.key.delegate.class");
        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(config);
        // Публикует метрики клиента Kafka для Prometheus, например отставание и скорость чтения.
        meterRegistry.ifAvailable(registry ->
                factory.addListener(new org.springframework.kafka.core.MicrometerConsumerListener<>(registry)));
        return factory;
    }

    /**
     * @param eventConsumerFactory фабрика строковых потребителей
     * @param eventErrorHandler обработчик повторов и DLT
     * @param properties параметры параллелизма
     * @return фабрика контейнеров для {@code @KafkaListener(containerFactory = ...)}
     */
    @Bean(CONTAINER_FACTORY)
    @ConditionalOnMissingBean(name = CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> eventListenerContainerFactory(
            ConsumerFactory<String, String> eventConsumerFactory,
            DefaultErrorHandler eventErrorHandler,
            EventConsumerProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(eventConsumerFactory);
        factory.setConcurrency(properties.getConcurrency());
        factory.setCommonErrorHandler(eventErrorHandler);
        factory.setRecordInterceptor(new CorrelationIdRecordInterceptor());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Для записи создаётся спан. Родительский контекст приходит в traceparent от outbox.
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
