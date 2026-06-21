package com.example.orders.support.idempotency;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Делает потребителя идемпотентным. Отметка события сохраняется в той же транзакции, что и изменение состояния.
 * <pre>
 * &#64;Transactional
 * void handle(EventEnvelope&lt;StockReserved&gt; event) {
 *     if (!processedEvents.markProcessed(event.eventId(), CONSUMER)) {
 *         return;                       // повтор уже обработанного события
 *     }
 *     ...                               // изменение состояния
 * }
 * </pre>
 * <p>{@code Propagation.MANDATORY} сразу отклоняет вызов без транзакции. Откат удаляет и отметку. Повторная доставка тогда сможет обработать событие.
 */
public class ProcessedEvents {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEvents.class);

    private final ProcessedEventRepository repository;

    /**
     * @param repository таблица {@code processed_events} этого сервиса
     */
    public ProcessedEvents(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Отмечает получение события потребителем.
     * @param eventId идентификатор из оболочки события
     * @param consumer имя потребителя, например {@code order.inventory-events}
     * @return {@code true} при первой доставке и {@code false}, если событие уже обработано
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String consumer) {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId must not be null");
        }
        ProcessedEvent.Key key = new ProcessedEvent.Key(eventId, consumer);
        if (repository.existsById(key)) {
            log.debug("Event {} was already processed by {}, ignoring the duplicate delivery", eventId, consumer);
            return false;
        }
        // Проверяем первичный ключ до фиксации транзакции, чтобы сразу обнаружить одновременный дубликат.
        repository.saveAndFlush(ProcessedEvent.of(eventId, consumer));
        return true;
    }

    /**
     * @param eventId идентификатор события
     * @param consumer имя потребителя
     * @return обрабатывал ли потребитель это событие
     */
    @Transactional(readOnly = true)
    public boolean isProcessed(UUID eventId, String consumer) {
        return eventId != null && repository.existsById(new ProcessedEvent.Key(eventId, consumer));
    }

    /**
     * Удаляет записи старше периода хранения.
     * @param retention период хранения отметки
     * @return число удалённых строк
     */
    @Transactional
    public int purgeOlderThan(Duration retention) {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(retention);
        int removed = repository.deleteProcessedBefore(cutoff);
        if (removed > 0) {
            log.info("Removed {} processed-event note(s) older than {}", removed, cutoff);
        }
        return removed;
    }
}
