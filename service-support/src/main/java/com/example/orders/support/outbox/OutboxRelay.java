package com.example.orders.support.outbox;

import com.example.orders.common.events.EventHeaders;
import com.example.orders.support.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Публикует подтверждённые записи {@code outbox_events} в Kafka.
 * <p>{@code SELECT ... FOR UPDATE SKIP LOCKED} даёт нескольким экземплярам обрабатывать разные строки. Сбой между ответом брокера и записью признака публикации приводит к повторной доставке. Потребитель дедуплицирует по идентификатору строки.
 * <p>Старые события публикуются первыми. Если отправка не удалась, более поздние события того же агрегата ждут следующего опроса. Неудачная попытка обновляет {@code attempts} и {@code last_error}, оставляя строку в очереди.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;

    /**
     * @param outbox репозиторий очереди
     * @param kafka строковый продюсер с доставкой как минимум один раз
     * @param batchSize число захватываемых строк
     * @param sendTimeout время ожидания одной операции
     * @param retention срок хранения опубликованных событий
     */
    public OutboxRelay(
            OutboxRepository outbox,
            KafkaTemplate<String, String> kafka,
            int batchSize,
            Duration sendTimeout,
            Duration retention) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
    }

    /**
     * Публикует пачку в одной транзакции. {@code SKIP LOCKED} удерживает строки до сохранения исхода отправки.
     * @return количество событий, принятых брокером
     */
    @Transactional
    public int publishPending() {
        List<OutboxEvent> batch = outbox.lockNextUnpublished(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        Set<UUID> stalledAggregates = new HashSet<>();
        int published = 0;
        for (OutboxEvent event : batch) {
            if (stalledAggregates.contains(event.getAggregateId())) {
                // Предыдущее событие этого агрегата не доставлено. Сохраняем порядок.
                continue;
            }
            if (publish(event)) {
                published++;
            }
            else {
                stalledAggregates.add(event.getAggregateId());
            }
        }
        if (published > 0) {
            log.debug("Outbox relay published {} of {} claimed event(s)", published, batch.size());
        }
        return published;
    }

    /**
     * Удаляет опубликованные записи старше периода хранения. Они временно служат журналом отправок.
     * @return число удалённых строк
     */
    @Transactional
    public int purgePublished() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(retention);
        int removed = outbox.deletePublishedBefore(cutoff);
        if (removed > 0) {
            log.info("Outbox cleanup removed {} event(s) published before {}", removed, cutoff);
        }
        return removed;
    }

    private boolean publish(OutboxEvent event) {
        String previousCorrelationId = MDC.get(CorrelationId.MDC_KEY);
        if (event.getCorrelationId() != null) {
            // Подставляем в лог идентификатор корреляции исходного запроса.
            MDC.put(CorrelationId.MDC_KEY, event.getCorrelationId());
        }
        try {
            kafka.send(toRecord(event)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished();
            return true;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            event.markFailed("Interrupted while waiting for the broker");
            log.warn("Interrupted while publishing outbox event {}", event.getId());
            return false;
        }
        catch (Exception e) {
            event.markFailed(describe(e));
            log.warn("Outbox event {} ({} for aggregate {}) could not be published, attempt {}: {}",
                    event.getId(), event.getEventType(), event.getAggregateId(), event.getAttempts(),
                    describe(e));
            return false;
        }
        finally {
            if (previousCorrelationId == null) {
                MDC.remove(CorrelationId.MDC_KEY);
            }
            else {
                MDC.put(CorrelationId.MDC_KEY, previousCorrelationId);
            }
        }
    }

    private static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.getTopic(),
                // По ключу агрегата Kafka направляет события одного заказа в одну партицию.
                event.getAggregateId().toString(),
                event.getPayload());
        record.headers().add(EventHeaders.EVENT_ID, bytes(event.getId().toString()));
        record.headers().add(EventHeaders.EVENT_TYPE, bytes(event.getEventType()));
        if (event.getCorrelationId() != null) {
            record.headers().add(EventHeaders.CORRELATION_ID, bytes(event.getCorrelationId()));
        }
        if (event.getTraceparent() != null) {
            record.headers().add("traceparent", bytes(event.getTraceparent()));
        }
        return record;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String describe(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
