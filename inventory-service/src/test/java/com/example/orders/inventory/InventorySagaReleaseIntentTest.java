package com.example.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.OrderItem;
import com.example.orders.common.events.ReleaseStock;
import com.example.orders.common.events.Topics;
import com.example.orders.inventory.domain.OrderReleaseIntentRepository;
import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.saga.InventorySagaTransactions;
import com.example.orders.support.idempotency.ProcessedEventRepository;
import com.example.orders.support.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Проверки освобождения, которое пришло раньше резервирования. Команды должны давать согласованный итог при разном порядке доставки и конкурентных вызовах.
 */
class InventorySagaReleaseIntentTest extends InventoryIntegrationTest {

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private OrderReleaseIntentRepository releaseIntents;

    @Autowired
    private InventorySagaTransactions inventorySaga;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void clearMessagingTables() {
        outbox.deleteAllInBatch();
        processedEvents.deleteAllInBatch();
        releaseIntents.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ освобождение до резервирования

    @Test
    void aReleaseThatArrivesBeforeItsOrderCreatedBlocksAllLaterReservations() throws Exception {
        Product product = givenProduct("SKU-INTENT-1", 10);
        UUID orderId = UUID.randomUUID();
        String reason = "Cancelled by the customer before the stock was reserved";

        // Освобождение приходит первым, хотя строк резерва ещё нет.
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-intent-release", new ReleaseStock(orderId, null, reason, List.of())));
        awaitUntil(Duration.ofSeconds(30), () -> releaseIntents.existsById(orderId));
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).isEmpty();
            Product after = products.findById(product.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
        });

        // Запоздалый OrderCreated отклоняется по записи об освобождении.
        publish(Topics.ORDER_EVENTS, orderId, orderCreatedEnvelope(orderId, product, 3));

        JsonNode event = awaitInventoryEvent(orderId, "StockRejected");
        assertThat(event.get("payload").get("reason").asText())
                .isEqualTo("The order was cancelled before its stock could be reserved");
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).isEmpty();
            assertThat(outboxTypes(orderId)).doesNotContain("StockReserved");
            Product after = products.findById(product.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
        });
    }

    @Test
    void afterAReserveWasReleasedADuplicateOrderCreatedCannotReserveAgain() throws Exception {
        Product product = givenProduct("SKU-INTENT-2", 10);
        UUID orderId = UUID.randomUUID();
        EventEnvelope<OrderCreated> original = orderCreatedEnvelope(orderId, product, 3);

        // Штатное резервирование, за которым приходит компенсация.
        publish(Topics.ORDER_EVENTS, orderId, original);
        awaitUntil(Duration.ofSeconds(30), () -> !reservations
                .findByOrderIdOrderByCreatedAtAsc(orderId).isEmpty());
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-intent-release", new ReleaseStock(orderId, null, "payment failed", List.of())));
        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().allMatch(row -> row.getStatus() == ReservationStatus.RELEASED));

        // Дубликат с прежним идентификатором уже отмечен как обработанный.
        publish(Topics.ORDER_EVENTS, orderId, original);
        // Повтор с новым идентификатором сверяется с сохранённым освобождением.
        publishOrderCreated(orderId, product, 3);

        JsonNode event = awaitInventoryEvent(orderId, "StockRejected");
        assertThat(event.get("payload").get("reason").asText())
                .isEqualTo("The order was cancelled before its stock could be reserved");
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            // Строки по-прежнему освобождены, остатки восстановлены.
            assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                    .hasSize(1)
                    .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
            Product after = products.findById(product.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
            // Отправлен один отказ для повторного события с новым идентификатором.
            assertThat(outboxTypes(orderId).stream().filter("StockRejected"::equals).count())
                    .isEqualTo(1);
        });
    }

    @Test
    void duplicateReleasesRestoreTheStockExactlyOnceAndKeepTheFirstIntent() throws Exception {
        Product product = givenProduct("SKU-INTENT-3", 10);
        UUID orderId = reserve(product, 4);

        // Два повтора с тем же идентификатором и два с новыми. Итог должен быть одинаковым.
        EventEnvelope<ReleaseStock> first =
                EventEnvelope.of("corr-intent-dup-1", new ReleaseStock(orderId, null, "cancelled", List.of()));
        publish(Topics.INVENTORY_COMMANDS, orderId, first);
        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().allMatch(row -> row.getStatus() == ReservationStatus.RELEASED));
        publish(Topics.INVENTORY_COMMANDS, orderId, first);
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-intent-dup-2", new ReleaseStock(orderId, null, "second reason", List.of())));
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-intent-dup-3", new ReleaseStock(orderId, null, "third reason", List.of())));

        awaitUntil(Duration.ofSeconds(30), () -> processedEvents.count() == 4);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            Product after = products.findById(product.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
            assertThat(releaseIntents.findById(orderId).orElseThrow().getReason()).isEqualTo("cancelled");
        });
    }

    @Test
    void aCancelledOrderThatWasNeverInThisDatabaseCannotReserveEither() throws Exception {
        // Неизвестный заказ тоже получает отметку освобождения. Поздний повтор не создаст резерв.
        UUID foreignOrder = UUID.randomUUID();
        Product product = givenProduct("SKU-INTENT-4", 10);

        publish(Topics.INVENTORY_COMMANDS, foreignOrder,
                EventEnvelope.of("corr-intent-foreign", new ReleaseStock(foreignOrder, null, "cancelled", List.of())));
        awaitUntil(Duration.ofSeconds(30), () -> releaseIntents.existsById(foreignOrder));
        publish(Topics.ORDER_EVENTS, foreignOrder, orderCreatedEnvelope(foreignOrder, product, 5));

        JsonNode event = awaitInventoryEvent(foreignOrder, "StockRejected");
        assertThat(event.get("payload").get("reason").asText())
                .isEqualTo("The order was cancelled before its stock could be reserved");
        // Данные исходного каталога остались без изменений.
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            Product after = products.findById(product.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
        });
    }

    @Test
    void aCommitStockThatArrivesAfterTheReleaseCannotReviveTheOrder() throws Exception {
        Product product = givenProduct("SKU-INTENT-5", 10);
        UUID orderId = reserve(product, 4);
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-late-commit-release", new ReleaseStock(orderId, null, "cancelled", List.of())));
        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().allMatch(row -> row.getStatus() == ReservationStatus.RELEASED));
        long handled = processedEvents.count();

        // Подтверждение продажи приходит после отмены.
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-late-commit", new CommitStock(orderId)));

        awaitUntil(Duration.ofSeconds(30), () -> processedEvents.count() == handled + 1);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                    .hasSize(1)
                    .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
            Product after = products.findById(product.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
            assertThat(releaseIntents.findById(orderId).orElseThrow().getReason()).isEqualTo("cancelled");
        });
    }

    // ------------------------------------------------------------------ конкурентные резервирование и освобождение

    @Test
    void aReserveAndItsReleaseRacingForEachOtherSettleOnCommittedData() throws Exception {
        int rounds = 10;
        int stockPerOrder = 6;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                Product product = givenProduct("SKU-INTENT-RACE-%d".formatted(round), stockPerOrder);
                UUID orderId = UUID.randomUUID();
                String reason = "race round " + round;

                // Запускаем шаги одновременно через те же обработчики, что использует Kafka.
                CountDownLatch start = new CountDownLatch(1);
                Callable<Boolean> reserveTask = () -> {
                    start.await();
                    inventorySaga.onOrderCreated(UUID.randomUUID(),
                            orderCreatedEventOf(orderId, product, 3, BigDecimal.TEN));
                    return true;
                };
                Callable<Boolean> releaseTask = () -> {
                    start.await();
                    inventorySaga.onReleaseStock(UUID.randomUUID(),
                            new ReleaseStock(orderId, null, reason, List.of()));
                    return true;
                };
                Future<Boolean> reserveFuture = pool.submit(reserveTask);
                Future<Boolean> releaseFuture = pool.submit(releaseTask);
                start.countDown();
                reserveFuture.get(60, TimeUnit.SECONDS);
                releaseFuture.get(60, TimeUnit.SECONDS);

                assertRaceInvariants(orderId, product, reason);
            }
        }
        finally {
            pool.shutdownNow();
        }
    }

    /**
     * После гонки допустимы только два состояния. Резерв отсутствует или уже освобождён.
     */
    private void assertRaceInvariants(UUID orderId, Product product, String releaseReason) {
        List<com.example.orders.inventory.domain.StockReservation> rows =
                reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
        Product after = products.findById(product.getId()).orElseThrow();
        List<String> answers = outboxTypes(orderId);

        if (rows.isEmpty()) {
            // Освобождение прошло первым. Резерв отклонён, причина сохранена.
            assertThat(answers).contains("StockRejected").doesNotContain("StockReserved");
            assertThat(after.getAvailableQuantity()).isEqualTo(6);
            assertThat(after.getReservedQuantity()).isZero();
        }
        else {
            // Резервирование прошло первым. Освобождение обработало сохранённые строки.
            assertThat(rows).allSatisfy(row ->
                    assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
            assertThat(answers).contains("StockReserved").doesNotContain("StockRejected");
            assertThat(after.getAvailableQuantity()).isEqualTo(6);
            assertThat(after.getReservedQuantity()).isZero();
        }
        // В обоих вариантах запись об освобождении должна существовать.
        assertThat(releaseIntents.findById(orderId).orElseThrow().getReason()).isEqualTo(releaseReason);
    }

    // ------------------------------------------------------------------ вспомогательные методы

    /** @return идентификатор заказа с резервом в {@code quantity} единиц, созданным обычным обработчиком */
    private UUID reserve(Product product, int quantity) throws Exception {
        UUID orderId = UUID.randomUUID();
        publishOrderCreated(orderId, product, quantity);
        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().anyMatch(row -> row.getStatus() == ReservationStatus.RESERVED));
        return orderId;
    }

    private void publishOrderCreated(UUID orderId, Product product, int quantity) throws Exception {
        publish(Topics.ORDER_EVENTS, orderId, orderCreatedEnvelope(orderId, product, quantity));
    }

    private static EventEnvelope<OrderCreated> orderCreatedEnvelope(UUID orderId, Product product, int quantity) {
        return EventEnvelope.of("corr-saga-intent",
                orderCreatedEventOf(orderId, product, quantity, BigDecimal.TEN));
    }

    private static OrderCreated orderCreatedEventOf(UUID orderId, Product product, int quantity, BigDecimal unitPrice) {
        OrderItem item = new OrderItem(product.getId(), product.getSku(), quantity, unitPrice);
        return new OrderCreated(orderId, "ORD-INTENT", UUID.randomUUID(),
                unitPrice.multiply(BigDecimal.valueOf(quantity)), "EUR", List.of(item));
    }

    private List<String> outboxTypes(UUID orderId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .map(row -> row.getEventType())
                .toList();
    }

    private void publish(String topic, UUID key, EventEnvelope<?> envelope) throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic, key.toString(), json.writeValueAsString(envelope));
            record.headers().add(EventHeaders.EVENT_ID, bytes(envelope.eventId().toString()));
            record.headers().add(EventHeaders.EVENT_TYPE, bytes(envelope.eventType()));
            record.headers().add(EventHeaders.CORRELATION_ID, bytes(envelope.correlationId()));
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

    private JsonNode awaitInventoryEvent(UUID orderId, String eventType) {
        Properties properties = new Properties();
        properties.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "inventory-intent-assert-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(Topics.INVENTORY_EVENTS));
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (!orderId.toString().equals(record.key())) {
                        continue;
                    }
                    JsonNode envelope = read(record.value());
                    if (eventType.equals(envelope.get("eventType").asText())) {
                        return envelope;
                    }
                }
            }
        }
        throw new AssertionError("No " + eventType + " for order " + orderId + " arrived on "
                + Topics.INVENTORY_EVENTS);
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        }
        catch (Exception e) {
            throw new AssertionError("Event body is not JSON: " + body, e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void awaitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(50);
        }
        throw new AssertionError("Condition did not become true within " + timeout);
    }

    private static void assertHoldsFor(Duration duration, Runnable assertion) {
        long deadline = System.nanoTime() + duration.toNanos();
        do {
            assertion.run();
            sleep(100);
        }
        while (System.nanoTime() < deadline);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", e);
        }
    }
}
