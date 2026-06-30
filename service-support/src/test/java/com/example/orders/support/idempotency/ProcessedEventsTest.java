package com.example.orders.support.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.orders.support.SupportIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Отметка дедупликации и изменение состояния должны фиксироваться или откатываться вместе.
 */
class ProcessedEventsTest extends SupportIntegrationTest {

    private static final String CONSUMER = "test.consumer";

    @Autowired
    private ProcessedEvents processedEvents;

    @Autowired
    private ProcessedEventRepository repository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetState() {
        repository.deleteAllInBatch();
    }

    @Test
    void theFirstDeliveryClaimsTheEventAndTheSecondIsANoOp() {
        UUID eventId = UUID.randomUUID();

        boolean first = claim(eventId, CONSUMER);
        boolean second = claim(eventId, CONSUMER);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(processedEvents.isProcessed(eventId, CONSUMER)).isTrue();
    }

    @Test
    void oneEventIsClaimedSeparatelyByEveryConsumer() {
        UUID eventId = UUID.randomUUID();

        boolean claimedByFirst = claim(eventId, "order.payment-events");
        // Другой потребитель того же сервиса может обработать то же событие.
        boolean claimedBySecond = claim(eventId, "notification.payments");

        assertThat(claimedByFirst).isTrue();
        assertThat(claimedBySecond).isTrue();

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void aRollbackLeavesNoNoteBehindSoTheRedeliveryDoesTheWork() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assertThat(processedEvents.markProcessed(eventId, CONSUMER)).isTrue();
            throw new IllegalStateException("the state change failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(processedEvents.isProcessed(eventId, CONSUMER)).isFalse();
        // Повторная доставка может выполнить работу после отката.
        assertThat(claim(eventId, CONSUMER)).isTrue();
    }

    /**
     * @param eventId идентификатор события
     * @param consumer логическое имя потребителя
     */
    private boolean claim(UUID eventId, String consumer) {
        Boolean claimed = transactions.execute(status -> processedEvents.markProcessed(eventId, consumer));
        return Boolean.TRUE.equals(claimed);
    }

    @Test
    void claimingAnEventOutsideATransactionIsRefused() {
        // Вызов без транзакции не должен пометить незавершённую работу обработанной.
        assertThatThrownBy(() -> processedEvents.markProcessed(UUID.randomUUID(), CONSUMER))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void theNoteIsWrittenWithTheEventIdAndTheConsumerAsItsKey() {
        UUID eventId = UUID.randomUUID();

        transactions.executeWithoutResult(status -> processedEvents.markProcessed(eventId, CONSUMER));

        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Long.class, eventId, CONSUMER);
        assertThat(rows).isEqualTo(1);
        ProcessedEvent stored = repository.findById(new ProcessedEvent.Key(eventId, CONSUMER)).orElseThrow();
        assertThat(stored.getEventId()).isEqualTo(eventId);
        assertThat(stored.getConsumer()).isEqualTo(CONSUMER);
        assertThat(stored.getProcessedAt()).isNotNull();
    }

    @Test
    void theRetentionSweepDropsOldNotesAndKeepsRecentOnes() {
        UUID oldEvent = UUID.randomUUID();
        UUID recentEvent = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            processedEvents.markProcessed(oldEvent, CONSUMER);
            processedEvents.markProcessed(recentEvent, CONSUMER);
        });
        jdbc.update("UPDATE processed_events SET processed_at = ? WHERE event_id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(30), oldEvent);

        assertThat(processedEvents.purgeOlderThan(Duration.ofDays(7))).isEqualTo(1);

        assertThat(processedEvents.isProcessed(oldEvent, CONSUMER)).isFalse();
        assertThat(processedEvents.isProcessed(recentEvent, CONSUMER)).isTrue();
    }
}
