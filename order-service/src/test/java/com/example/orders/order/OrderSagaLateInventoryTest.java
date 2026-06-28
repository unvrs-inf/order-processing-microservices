package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.StockRejected;
import com.example.orders.common.events.StockReserved;
import com.example.orders.common.events.Topics;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.RefundStatus;
import com.example.orders.support.correlation.CorrelationId;
import com.example.orders.support.idempotency.ProcessedEventRepository;
import com.example.orders.support.outbox.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Проверки компенсации позднего {@code StockReserved} на стороне order-service.
 */
class OrderSagaLateInventoryTest extends OrderIntegrationTest {

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void clearDeduplicationLog() {
        processedEvents.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ поздний резерв

    @Test
    void aStockReservedForACancelledOrderIsRememberedAndReleasedAgain() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "119.00", 1, "corr-late-reserve");

        // Saga отменяет заказ до ответа склада.
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk());
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        // Отмена отправила освобождение до получения идентификатора резерва.
        assertThat(countOutbox(orderId, "ReleaseStock")).isEqualTo(1);

        // Запоздалый резерв достигает оркестратора после отмены.
        UUID reservationId = UUID.randomUUID();
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-1",
                new StockReserved(orderId, reservationId, List.of())));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getReservationId() != null);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            Order order = order(orderId);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getReservationId()).isEqualTo(reservationId);
            assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.NONE);
        });
        // Запись позднего резерва и команда освобождения фиксируются вместе.
        assertThat(countOutbox(orderId, "ReleaseStock")).isEqualTo(2);
        assertThat(outboxTypes(orderId)).doesNotContain("ProcessPayment", "OrderConfirmed", "CommitStock");
        // Компенсация не породила повторное уведомление об отмене.
        assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
    }

    @Test
    void furtherLateStockReservedEventsForTheSameOrderQueueNothingMore() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "119.00", 1, "corr-late-reserve-dupes");
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-dupes-a",
                new StockReserved(orderId, UUID.randomUUID(), List.of())));
        // Дождёмся обработки первого ответа, чтобы повтор был именно повтором.
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getReservationId() != null);
        UUID remembered = order(orderId).getReservationId();

        // Брокер повторяет идентификатор события, а склад отвечает заново с новым идентификатором.
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-dupes-a2",
                new StockReserved(orderId, remembered, List.of())));
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-dupes-b",
                new StockReserved(orderId, UUID.randomUUID(), List.of())));

        awaitUntil(Duration.ofSeconds(45), () -> processedEvents.count() == 3);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.NONE);
            // Одна команда от отмены и одна от первого позднего ответа.
            assertThat(countOutbox(orderId, "ReleaseStock")).isEqualTo(2);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    @Test
    void aStockReservedForARejectedOrderIsRememberedAndReleasedToo() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID(), "119.00", 1, "corr-late-reserve-rejected");

        // Склад сначала отказал. REJECTED — конечный статус без собственной команды освобождения.
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-rejected-no",
                new StockRejected(orderId, "Insufficient stock (SKU-X: requested 1, available 0)", List.of("SKU-X"))));
        awaitStatus(orderId, OrderStatus.REJECTED);
        assertThat(countOutbox(orderId, "ReleaseStock")).isZero();

        // Поздний повтор OrderCreated обработан после окончания окна дедупликации.
        UUID reservationId = UUID.randomUUID();
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-rejected-yes",
                new StockReserved(orderId, reservationId, List.of())));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getReservationId() != null);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            Order order = order(orderId);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.getReservationId()).isEqualTo(reservationId);
            assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.NONE);
            assertThat(countOutbox(orderId, "ReleaseStock")).isEqualTo(1);
        });
        assertThat(outboxTypes(orderId)).doesNotContain("ProcessPayment", "OrderConfirmed", "CommitStock");

        // Повторный ответ по тому же резерву не добавляет команд.
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-rejected-again",
                new StockReserved(orderId, reservationId, List.of())));
        awaitUntil(Duration.ofSeconds(45), () -> processedEvents.count() == 3);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(countOutbox(orderId, "ReleaseStock")).isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------ вспомогательные методы

    private Order order(UUID orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    private UUID placeOrder(UUID user, String unitPrice, int quantity, String correlationId) throws Exception {
        UUID product = givenCatalogProduct("SKU-LATERSV-" + UUID.randomUUID().toString().substring(0, 6),
                "Late reservation product", unitPrice);
        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .header(CorrelationId.HEADER, correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(parse(body).get("id").asText());
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
