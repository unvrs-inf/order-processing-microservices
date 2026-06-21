package com.example.orders.support.messaging;

import com.example.orders.support.correlation.CorrelationId;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Переносит идентификатор корреляции из заголовка Kafka в MDC на время обработки записи.
 */
public class CorrelationIdRecordInterceptor implements RecordInterceptor<String, String> {

    @Override
    public ConsumerRecord<String, String> intercept(
            ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        MDC.put(CorrelationId.MDC_KEY, CorrelationId.sanitise(EventEnvelopeReader.correlationIdOf(record)));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        MDC.remove(CorrelationId.MDC_KEY);
    }
}
