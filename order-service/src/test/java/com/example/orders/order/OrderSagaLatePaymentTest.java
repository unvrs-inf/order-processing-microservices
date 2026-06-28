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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Регрессионные проверки оплаты, завершившейся после отмены или тайм-аута Saga. Возврат должен дойти до завершения, включая повтор команды и одновременный запрос отмены.
 */
class OrderSagaLatePaymentTest extends OrderIntegrationTest {

    @Autowired
    private OrderSaga saga;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void clearDeduplicationLog() {
        processedEvents.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ поздняя оплата

    @Test
    void aPaymentThatCompletesAfterTheCustomerCancelledIsRefundedAndTracked() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "119.00", 1, "corr-late-cancel");

        // Отменяем заказ, который ещё не получил ответ об оплате.
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk());
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.NONE);
        assertThat(outboxTypes(orderId)).contains("ReleaseStock", "OrderCancelled")
                .doesNotContain("RefundPayment");

        UUID paymentId = UUID.randomUUID();
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-payment",
                new PaymentCompleted(orderId, paymentId, new BigDecimal("119.00"), "EUR")));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REQUESTED);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order(orderId).getPaymentId()).isEqualTo(paymentId);

        // Платёж отслеживается и запрашивается возврат. Статус заказа остаётся отменённым.
        JsonNode refund = parse(awaitOutbox(orderId, "RefundPayment").getPayload()).get("payload");
        assertThat(refund.get("paymentId").asText()).isEqualTo(paymentId.toString());
        assertThat(refund.get("amount").asDouble()).isEqualTo(119.00);
        assertThat(outboxTypes(orderId)).doesNotContain("OrderConfirmed", "CommitStock");
        assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);

        // Ответ завершает возврат, не меняя статус и не публикуя повторную отмену.
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-refunded",
                new PaymentRefunded(orderId, paymentId, new BigDecimal("119.00"), "EUR",
                        "The order was already cancelled when its payment completed")));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
    }

    @Test
    void aPaymentThatCompletesAfterTheTimeoutSweepIsRefundedAndTracked() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID(), "119.00", 1, "corr-late-timeout");

        // Планировщик отменил заказ с компенсацией. Ответа о платеже пока нет.
        assertThat(saga.timeOut(orderId, "Test: the sweep gave up")).isTrue();
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(countOutbox(orderId, "RefundPayment")).isZero();

        UUID paymentId = UUID.randomUUID();
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-timeout-payed",
                new PaymentCompleted(orderId, paymentId, new BigDecimal("119.00"), "EUR")));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REQUESTED);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(1);
        assertThat(countOutbox(orderId, "CommitStock")).isZero();

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-timeout-refunded",
                new PaymentRefunded(orderId, paymentId, new BigDecimal("119.00"), "EUR", "Timed out")));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        // Уведомление об отмене уже отправлено. При возврате оно не повторяется.
        assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void aPaymentConfirmedBeforeTheCancellationWaitsForItsRefundAndThenFinishes() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = confirmedOrder(user, "corr-pay-then-cancel");

        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk());

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
        assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(1);
        // Отмена подтверждается после завершения возврата.
        assertThat(countOutbox(orderId, "OrderCancelled")).isZero();

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-pay-then-cancel-refunded",
                new PaymentRefunded(orderId, order(orderId).getPaymentId(),
                        new BigDecimal("298.00"), "EUR", "Cancelled by the customer")));

        awaitStatus(orderId, OrderStatus.CANCELLED);
        assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REFUNDED);
        assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ повторные доставки

    @Test
    void duplicateLatePaymentsQueueExactlyOneRefund() throws Exception {
        UUID orderId = cancelledInFlightOrder("corr-late-dupes");
        UUID paymentId = UUID.randomUUID();

        // Две доставки с одним идентификатором и две с новыми описывают один и тот же платёж.
        EventEnvelope<PaymentCompleted> first = EventEnvelope.of("corr-late-dupes-1",
                new PaymentCompleted(orderId, paymentId, new BigDecimal("119.00"), "EUR"));
        publish(Topics.PAYMENT_EVENTS, orderId, first);
        publish(Topics.PAYMENT_EVENTS, orderId, first);
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-dupes-2",
                new PaymentCompleted(orderId, paymentId, new BigDecimal("119.00"), "EUR")));
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-dupes-3",
                new PaymentCompleted(orderId, paymentId, new BigDecimal("119.00"), "EUR")));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REQUESTED);
        assertHoldsFor(Duration.ofSeconds(3), () -> {
            assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(1);
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        });
        // Отмечены три разных события. Повтор первого не добавляет запись.
        assertThat(processedEvents.count()).isEqualTo(3);

        // Один возврат завершает операцию, даже если уведомление приходит несколько раз.
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-dupes-r1",
                new PaymentRefunded(orderId, paymentId, new BigDecimal("119.00"), "EUR", "cancelled")));
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-dupes-r2",
                new PaymentRefunded(orderId, paymentId, new BigDecimal("119.00"), "EUR", "cancelled")));
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-dupes-r3",
                new PaymentRefunded(orderId, paymentId, new BigDecimal("119.00"), "EUR", "cancelled")));

        awaitUntil(Duration.ofSeconds(45), () -> processedEvents.count() == 6);
        assertHoldsFor(Duration.ofSeconds(3), () -> {
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REFUNDED);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    @Test
    void aPaymentRefundedForAnOrderThatNeverHadATrackedRefundIsHarmless() throws Exception {
        // Клиент отменил заказ до списания. Saga не запрашивала возврат.
        UUID orderId = cancelledInFlightOrder("corr-norefund");

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-norefund-r",
                new PaymentRefunded(orderId, UUID.randomUUID(), new BigDecimal("50.00"), "EUR", "bogus")));

        awaitUntil(Duration.ofSeconds(45), () -> processedEvents.count() == 1);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.NONE);
            assertThat(order(orderId).getPaymentId()).isNull();
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------ прерванный возврат

    @Test
    void anInterruptedRefundIsRetriedByTheSweepAndThenCompletedExactlyOnce() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = confirmedOrder(user, "corr-refund-retry");

        // Клиент отменил подтверждённый заказ. Команда ушла, но ответа нет.
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk());
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
        assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(1);
        OffsetDateTime firstAttempt = order(orderId).getRefundRetryAt();

        // CANCELLATION_PENDING означает, что заказ ждёт возврата. Обычный тайм-аут не должен менять это состояние.
        assertThat(saga.timeOut(orderId, "Test: the sweep gave up on a waiting order")).isFalse();
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
        assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(1);
        // Задача восстановления повторяет возврат только после выделенного интервала.
        assertThat(saga.recoverOutstandingRefund(orderId, "Test: too early",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10))).isFalse();
        assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(1);
        // Открываем окно повторной попытки, передавая соответствующий срок.
        assertThat(saga.recoverOutstandingRefund(orderId, "Test: the refund sweep retry", firstAttempt)).isTrue();
        // Повтор команды сохраняет состояние ожидания.
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
        // Новая команда и время попытки записаны в одной транзакции.
        assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(2);
        assertThat(order(orderId).getRefundRetryAt()).isAfter(firstAttempt);
        // Отмену завершает ответ о возврате, а не проверка тайм-аутов.
        assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(0);

        UUID paymentId = order(orderId).getPaymentId();
        // Возврат завершён один раз. Повторное уведомление не создаёт нового перехода.
        BigDecimal refunded = totalOf(orderId);
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-refund-retry-1",
                new PaymentRefunded(orderId, paymentId, refunded, "EUR", "cancelled")));
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-refund-retry-2",
                new PaymentRefunded(orderId, paymentId, refunded, "EUR", "cancelled")));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        // Обработаны два подготовительных события и оба сообщения о возврате.
        awaitUntil(Duration.ofSeconds(45), () -> processedEvents.count() == 4);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------ гонка отмены и оплаты

    @Test
    void concurrentCancellationAndPaymentCompletionProduceExactlyOneRefund() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int rounds = 8;
            for (int round = 0; round < rounds; round++) {
                UUID user = UUID.randomUUID();
                String correlationId = "corr-concurrent-" + round;
                // Сначала переводим заказ в PAYMENT_PENDING. В этом состоянии допустим успешный платёж.
                UUID orderId = reservedOrder(user, correlationId);
                UUID paymentId = UUID.randomUUID();
                BigDecimal total = order(orderId).getTotalAmount();
                PaymentCompleted payment = new PaymentCompleted(orderId, paymentId, total, "EUR");

                CountDownLatch start = new CountDownLatch(1);
                // Одна задача отменяет через HTTP, другая обрабатывает событие оплаты.
                Callable<Boolean> cancelTask = () -> {
                    start.await();
                    mvc.perform(post("/api/orders/{id}/cancel", orderId)
                                    .header("Authorization", "Bearer " + token(user, "USER")))
                            .andExpect(status().isOk());
                    return true;
                };
                Callable<Boolean> payTask = () -> {
                    start.await();
                    saga.paymentCompleted(UUID.randomUUID(), payment);
                    return true;
                };
                Future<Boolean> cancelFuture = pool.submit(cancelTask);
                Future<Boolean> payFuture = pool.submit(payTask);
                start.countDown();
                cancelFuture.get(60, TimeUnit.SECONDS);
                payFuture.get(60, TimeUnit.SECONDS);

                assertRaceInvariants(orderId, paymentId);
            }
        }
        finally {
            pool.shutdownNow();
        }
    }

    /** Завершает возврат заказа после гонки отмены и оплаты. */
    private void assertRaceInvariants(UUID orderId, UUID paymentId) {
        Order order = orders.findById(orderId).orElseThrow();
        List<String> types = outboxTypes(orderId);
        long ordersConfirmed = types.stream().filter("OrderConfirmed"::equals).count();
        long refundsQueued = types.stream().filter("RefundPayment"::equals).count();
        long ordersCancelled = types.stream().filter("OrderCancelled"::equals).count();
        long commitsQueued = types.stream().filter("CommitStock"::equals).count();

        // Оба порядка операций сходятся к одному отслеживаемому возврату.
        assertThat(refundsQueued)
                .as("order %s: types %s", orderId, types)
                .isEqualTo(1);
        assertThat(ordersConfirmed).isLessThanOrEqualTo(1);
        assertThat(ordersCancelled).isLessThanOrEqualTo(1);
        assertThat(order.getStatus()).isIn(OrderStatus.CANCELLATION_PENDING, OrderStatus.CANCELLED);
        assertThat(order.getPaymentId()).isEqualTo(paymentId);
        assertThat(order.getRefundStatus()).isIn(RefundStatus.REQUESTED, RefundStatus.REFUNDED);

        if (ordersConfirmed > 0) {
            assertThat(commitsQueued).isEqualTo(1);
        }
        else {
            // Отмена обработалась первой. Освобождённый резерв не подтверждается.
            assertThat(commitsQueued).isZero();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        // Завершаем возврат перед следующей итерацией.
        if (order.getStatus() == OrderStatus.CANCELLATION_PENDING) {
            publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-race-refunded-" + orderId,
                    new PaymentRefunded(orderId, paymentId, totalOf(orderId), "EUR", "cancelled")));
            awaitUntil(Duration.ofSeconds(45), () -> orders.findById(orderId)
                    .map(found -> found.getRefundStatus() == RefundStatus.REFUNDED
                            && found.getStatus() == OrderStatus.CANCELLED)
                    .orElse(false));
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ вспомогательные методы

    private BigDecimal totalOf(UUID orderId) {
        return orders.findById(orderId).orElseThrow().getTotalAmount();
    }

    private Order order(UUID orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    /** @return заказ, отменённый до ответа сервисов */
    private UUID cancelledInFlightOrder(String correlationId) throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "119.00", 1, correlationId);
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
        awaitStatus(orderId, OrderStatus.CANCELLED);
        return orderId;
    }

    private UUID placeOrder(UUID user, String unitPrice, int quantity, String correlationId) throws Exception {
        UUID product = givenCatalogProduct("SKU-LATE-" + UUID.randomUUID().toString().substring(0, 6),
                "Late payment product", unitPrice);
        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .header(CorrelationId.HEADER, correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(parse(body).get("id").asText());
    }

    /** @return заказ в {@code PAYMENT_PENDING} после штатного перехода Saga */
    private UUID reservedOrder(UUID user, String correlationId) throws Exception {
        UUID orderId = placeOrder(user, "119.00", 2, correlationId);
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of(correlationId,
                new StockReserved(orderId, UUID.randomUUID(), List.of())));
        awaitStatus(orderId, OrderStatus.PAYMENT_PENDING);
        return orderId;
    }

    /** @return заказ в {@code CONFIRMED} с идентификатором платежа после штатной обработки */
    private UUID confirmedOrder(UUID user, String correlationId) throws Exception {
        UUID orderId = reservedOrder(user, correlationId);
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of(correlationId,
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("238.00"), "EUR")));
        awaitStatus(orderId, OrderStatus.CONFIRMED);
        return orderId;
    }

    private void awaitStatus(UUID orderId, OrderStatus expected) {
        awaitUntil(Duration.ofSeconds(45), () -> orders.findById(orderId)
                .map(found -> found.getStatus() == expected)
                .orElse(false));
    }

    private OutboxEvent awaitOutbox(UUID orderId, String eventType) {
        awaitUntil(Duration.ofSeconds(45), () -> findOutbox(orderId, eventType).isPresent());
        return findOutbox(orderId, eventType).orElseThrow();
    }

    private Optional<OutboxEvent> findOutbox(UUID orderId, String eventType) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .filter(row -> eventType.equals(row.getEventType()))
                .findFirst();
    }

    private long countOutbox(UUID orderId, String eventType) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .filter(row -> eventType.equals(row.getEventType()))
                .count();
    }

    private List<String> outboxTypes(UUID orderId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .map(OutboxEvent::getEventType)
                .toList();
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
}
