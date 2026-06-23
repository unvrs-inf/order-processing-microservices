package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.PaymentCompleted;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.StockReserved;
import com.example.orders.common.events.Topics;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.RefundStatus;
import com.example.orders.order.saga.OrderSaga;
import com.example.orders.support.correlation.CorrelationId;
import com.example.orders.support.idempotency.ProcessedEventRepository;
import com.example.orders.support.outbox.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Проверяет соответствие платежа заказу и восстановление потерянных запросов возврата.
 */
@TestPropertySource(properties = {
        "orders.saga.refund-retry-interval=PT1S",
        "orders.saga.sweep-interval-ms=300"
})
// Контекст останавливается после класса, чтобы быстрый планировщик не оставался в кеше других тестов.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderRefundRecoveryTest extends OrderIntegrationTest {

    @Autowired
    private OrderSaga saga;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void clearDeduplicationLog() {
        processedEvents.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ проверка идентификатора платежа

    @Test
    void aForeignRefundAnswerCannotSettleTheTrackedRefundOfACancellingOrder() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = cancellationPendingOrder(user, "corr-foreign-pending");
        UUID trackedPayment = order(orderId).getPaymentId();
        BigDecimal total = order(orderId).getTotalAmount();

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-foreign-pending-refund",
                new PaymentRefunded(orderId, UUID.randomUUID(), total, "EUR",
                        "some other order's refund")));

        // Ответ по чужому платежу получен и отброшен. Состояние заказа и outbox сохраняются.
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
            assertThat(order(orderId).getPaymentId()).isEqualTo(trackedPayment);
            assertThat(countOutbox(orderId, "OrderCancelled")).isZero();
            everyRefundNamesPayment(orderId, trackedPayment);
        });

        // Ответ по отслеживаемому платежу завершает возврат.
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-foreign-pending-matched",
                new PaymentRefunded(orderId, trackedPayment, total, "EUR", "refund")));
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        assertHoldsFor(Duration.ofSeconds(1), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
            everyRefundNamesPayment(orderId, trackedPayment);
        });
    }

    @Test
    void aForeignRefundAnswerDoesNothingToACancelledOrderOwingRefund() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = cancelledOrderLateCharged(user, "corr-foreign-cancelled");
        UUID trackedPayment = order(orderId).getPaymentId();
        BigDecimal total = order(orderId).getTotalAmount();

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-foreign-cancelled-refund",
                new PaymentRefunded(orderId, UUID.randomUUID(), total, "EUR",
                        "some other order's refund")));

        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
            assertThat(order(orderId).getPaymentId()).isEqualTo(trackedPayment);
            // Отмена была объявлена один раз после запроса клиента. Чужой возврат не вызывает повтор.
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
            everyRefundNamesPayment(orderId, trackedPayment);
        });

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-foreign-cancelled-matched",
                new PaymentRefunded(orderId, trackedPayment, total, "EUR", "refund")));
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        assertHoldsFor(Duration.ofSeconds(1), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------ восстановление возврата

    @Test
    void theScheduledSweepRecommandsALostRefundForACancelledOrder() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = cancelledOrderLateCharged(user, "corr-recovered-refund");
        UUID trackedPayment = order(orderId).getPaymentId();
        BigDecimal total = order(orderId).getTotalAmount();

        // Ответа на первую команду нет. После одного интервала планировщик повторяет запрос.
        awaitUntil(Duration.ofSeconds(45), () -> countOutbox(orderId, "RefundPayment") >= 2);

        JsonNode lastRefund = parse(lastOutboxRow(orderId, "RefundPayment").getPayload()).get("payload");
        assertThat(lastRefund.get("paymentId").asText()).isEqualTo(trackedPayment.toString());
        assertThat(lastRefund.get("amount").asDouble()).isEqualTo(total.doubleValue());
        assertHoldsFor(Duration.ofSeconds(1), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
            // Запрос возврата повторяется, уведомление об отмене остаётся единственным.
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
            everyRefundNamesPayment(orderId, trackedPayment);
        });
    }

    @Test
    void aCancellingOrderSurvivesRepeatedlyInterruptedRefundAttempts() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = cancellationPendingOrder(user, "corr-interrupted-refunds");
        UUID trackedPayment = order(orderId).getPaymentId();
        BigDecimal total = order(orderId).getTotalAmount();

        // Исходная команда и два повтора проверяют восстановление после нескольких потерянных ответов.
        awaitUntil(Duration.ofSeconds(45), () -> countOutbox(orderId, "RefundPayment") >= 3);
        assertHoldsFor(Duration.ofSeconds(1), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
            assertThat(countOutbox(orderId, "OrderCancelled")).isZero();
            everyRefundNamesPayment(orderId, trackedPayment);
        });

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-interrupted-refunds-matched",
                new PaymentRefunded(orderId, trackedPayment, total, "EUR", "refund")));
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        assertHoldsFor(Duration.ofSeconds(1), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            // Уведомление публикуется один раз, при завершении возврата.
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    @Test
    void aSettledRefundStopsTheRecoverySweep() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = cancelledOrderLateCharged(user, "corr-settled-refund");
        UUID trackedPayment = order(orderId).getPaymentId();
        BigDecimal total = order(orderId).getTotalAmount();

        // Незавершённый возврат будет запрошен снова.
        awaitUntil(Duration.ofSeconds(45), () -> countOutbox(orderId, "RefundPayment") >= 2);

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-settled-refunded",
                new PaymentRefunded(orderId, trackedPayment, total, "EUR", "refund")));
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);

        long commandsWhenSettled = countOutbox(orderId, "RefundPayment");
        // Трёх секунд хватает на несколько проходов. Завершённый возврат не повторяется.
        assertHoldsFor(Duration.ofSeconds(3), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REFUNDED);
            assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(commandsWhenSettled);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------ безопасность повтора

    @Test
    void recoveryIsBookedAgainstTheRetryWindowAndNeverDuplicatesEffects() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = cancelledOrderLateCharged(user, "corr-throttled-recovery");
        UUID trackedPayment = order(orderId).getPaymentId();

        // Прямой вызов использует тот же срок dueBefore, что и планировщик.
        OffsetDateTime lastAttempt = order(orderId).getRefundRetryAt();
        assertThat(lastAttempt).isNotNull();

        // Только один конкурентный вызов фиксирует попытку в текущем окне.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int requeues;
        try {
            Callable<Boolean> attempt = () -> saga.recoverOutstandingRefund(
                    orderId, "Test: concurrent recovery", lastAttempt);
            Future<Boolean> first = pool.submit(attempt);
            Future<Boolean> second = pool.submit(attempt);
            int trues = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(trues).as("exactly one attempt may win a window").isEqualTo(1);

            // В уже записанном окне новый запрос не отправляется.
            assertThat(saga.recoverOutstandingRefund(
                    orderId, "Test: immediate repeat", lastAttempt)).isFalse();

            assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(2);
            requeues = 2;
        }
        finally {
            pool.shutdownNow();
        }
        // Во всех повторных командах указан отслеживаемый платёж.
        everyRefundNamesPayment(orderId, trackedPayment);

        // После следующего интервала планировщик повторяет запрос.
        awaitUntil(Duration.ofSeconds(45), () -> countOutbox(orderId, "RefundPayment") > requeues);
        assertHoldsFor(Duration.ofSeconds(1), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    @Test
    void ordersWithNoOutstandingRefundAreNotTouchedByTheRecoverySweep() throws Exception {
        // Подтверждённый заказ без возврата не обрабатывается планировщиком.
        UUID user = UUID.randomUUID();
        UUID confirmedId = confirmedOrder(user, "corr-untouched-confirmed");
        List<String> confirmedTypes = outboxTypes(confirmedId);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(outboxTypes(confirmedId)).isEqualTo(confirmedTypes);
            assertThat(order(confirmedId).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order(confirmedId).getRefundStatus()).isEqualTo(RefundStatus.NONE);
        });

        // Отмена неоплаченного заказа не требует возврата.
        UUID user2 = UUID.randomUUID();
        UUID cancelledId = placeOrder(user2, "119.00", 2, "corr-untouched-cancelled");
        cancelByCustomer(cancelledId, user2);
        awaitStatus(cancelledId, OrderStatus.CANCELLED);
        List<String> cancelledTypes = outboxTypes(cancelledId);
        assertThat(cancelledTypes).doesNotContain("RefundPayment");
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(outboxTypes(cancelledId)).isEqualTo(cancelledTypes);
            assertThat(order(cancelledId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(cancelledId).getRefundStatus()).isEqualTo(RefundStatus.NONE);
        });
    }

    // ------------------------------------------------------------------ тестовые данные

    /**
     * @return заказ, отменённый владельцем в процессе оформления и уже объявленный отменённым
     */
    private UUID cancelledOrderLateCharged(UUID user, String correlationId) throws Exception {
        UUID orderId = placeOrder(user, "199.00", 1, correlationId);
        cancelByCustomer(orderId, user);
        awaitStatus(orderId, OrderStatus.CANCELLED);

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of(correlationId + "-late-pay",
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("199.00"), "EUR")));
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REQUESTED);
        return orderId;
    }

    /**
     * @return ранее подтверждённый заказ, отменённый владельцем и ожидающий возврата
     */
    private UUID cancellationPendingOrder(UUID user, String correlationId) throws Exception {
        UUID orderId = confirmedOrder(user, correlationId);
        cancelByCustomer(orderId, user);
        awaitStatus(orderId, OrderStatus.CANCELLATION_PENDING);
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REQUESTED);
        return orderId;
    }

    private void cancelByCustomer(UUID orderId, UUID user) throws Exception {
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk());
    }

    private UUID confirmedOrder(UUID user, String correlationId) throws Exception {
        UUID orderId = placeOrder(user, "119.00", 2, correlationId);
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of(correlationId,
                new StockReserved(orderId, UUID.randomUUID(), List.of())));
        awaitStatus(orderId, OrderStatus.PAYMENT_PENDING);
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of(correlationId,
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("238.00"), "EUR")));
        awaitStatus(orderId, OrderStatus.CONFIRMED);
        return orderId;
    }

    private UUID placeOrder(UUID user, String unitPrice, int quantity, String correlationId) throws Exception {
        UUID product = givenCatalogProduct("SKU-RECOVERY-" + UUID.randomUUID().toString().substring(0, 6),
                "Recovery product", unitPrice);
        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .header(CorrelationId.HEADER, correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(parse(body).get("id").asText());
    }

    // ------------------------------------------------------------------ проверки и инфраструктура

    /** @param paymentId идентификатор во всех командах {@code RefundPayment} по заказу */
    private void everyRefundNamesPayment(UUID orderId, UUID paymentId) {
        for (OutboxEvent row : outbox.findByAggregateIdOrderByCreatedAtAsc(orderId)) {
            if ("RefundPayment".equals(row.getEventType())) {
                assertThat(quietParse(row.getPayload()).get("payload").get("paymentId").asText())
                        .isEqualTo(paymentId.toString());
            }
        }
    }

    private JsonNode quietParse(String payload) {
        try {
            return parse(payload);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Outbox payload is not JSON", e);
        }
    }

    private OutboxEvent lastOutboxRow(UUID orderId, String eventType) {
        List<OutboxEvent> rows = outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .filter(row -> eventType.equals(row.getEventType()))
                .toList();
        assertThat(rows).isNotEmpty();
        return rows.get(rows.size() - 1);
    }

    private List<OutboxEvent> findOutbox(UUID orderId, String eventType) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .filter(row -> eventType.equals(row.getEventType()))
                .toList();
    }

    private long countOutbox(UUID orderId, String eventType) {
        return findOutbox(orderId, eventType).size();
    }

    private List<String> outboxTypes(UUID orderId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId)
                .stream().map(OutboxEvent::getEventType).toList();
    }

    private Order order(UUID orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    private void awaitStatus(UUID orderId, OrderStatus expected) {
        awaitUntil(Duration.ofSeconds(45), () -> orders.findById(orderId)
                .map(found -> found.getStatus() == expected)
                .orElse(false));
    }

    private static void assertHoldsFor(Duration duration, Runnable assertion) {
        long deadline = System.nanoTime() + duration.toNanos();
        do {
            assertion.run();
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting", e);
            }
        }
        while (System.nanoTime() < deadline);
    }

    private void publish(String topic, UUID key, EventEnvelope<?> envelope) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic, key.toString(), json.writeValueAsString(envelope));
            record.headers().add(EventHeaders.EVENT_ID,
                    envelope.eventId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.EVENT_TYPE,
                    envelope.eventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.CORRELATION_ID,
                    envelope.correlationId().getBytes(StandardCharsets.UTF_8));
            producer.send(record).get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while producing", e);
        }
        catch (Exception e) {
            throw new AssertionError("Could not produce onto " + topic, e);
        }
    }
}
