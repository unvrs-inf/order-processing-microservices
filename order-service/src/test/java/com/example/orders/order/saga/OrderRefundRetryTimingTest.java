package com.example.orders.order.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.order.OrderIntegrationTest;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.RefundStatus;
import com.example.orders.support.outbox.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Проверяет, что тайм-аут Saga и период повторного возврата действуют независимо.
 */
@TestPropertySource(properties = {
        "orders.saga.timeout=PT5S",
        "orders.saga.refund-retry-interval=PT20S",
        // Оба прохода планировщика запускаются в тесте вручную.
        "orders.saga.timeout-sweeper-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderRefundRetryTimingTest extends OrderIntegrationTest {

    private static final String REFUND = "RefundPayment";

    /** Тайм-аут заказа 5 с прошёл, интервал возврата 20 с ещё нет. */
    private static final Duration NOT_DUE_AGE = Duration.ofSeconds(12);

    /** Интервал возврата 20 с прошёл, стандартный интервал другого контекста 30 с ещё нет. */
    private static final Duration DUE_AGE = Duration.ofSeconds(25);

    /** Обычный зависший заказ проходит тайм-аут и моложе ожидающих возвратов. */
    private static final Duration STUCK_AGE = Duration.ofSeconds(8);

    @Value("${orders.saga.timeout}")
    private Duration timeout;

    @Value("${orders.saga.refund-retry-interval}")
    private Duration refundRetryInterval;

    @Autowired
    private OrderSaga saga;

    @Autowired
    private JdbcTemplate jdbc;

    private OrderSagaTimeoutSweeper sweeper;

    @BeforeEach
    void buildSweeper() {
        // Предусловие проверки. Интервал возврата больше тайм-аута заказа.
        assertThat(refundRetryInterval).isGreaterThan(timeout);
        assertThat(NOT_DUE_AGE).isGreaterThan(timeout).isLessThan(refundRetryInterval);
        assertThat(DUE_AGE).isGreaterThan(refundRetryInterval);
        assertThat(STUCK_AGE).isGreaterThan(timeout).isLessThan(NOT_DUE_AGE);
        sweeper = new OrderSagaTimeoutSweeper(orders, saga, timeout, refundRetryInterval);
    }

    // ------------------------------------------------------------------ до срока повторной попытки

    @Test
    void aRefundThatIsNotDueYetIsRequeuedByNeitherScan() {
        UUID waiting = refundOwingOrder(OrderStatus.CANCELLATION_PENDING);
        UUID cancelled = refundOwingOrder(OrderStatus.CANCELLED);
        age(NOT_DUE_AGE, waiting, cancelled);
        Book waitingBefore = book(waiting);
        Book cancelledBefore = book(cancelled);
        // Строки старше тайм-аута заказа.
        assertThat(waitingBefore.updatedAt()).isBefore(now().minus(timeout));
        // Последняя команда возврата укладывается в свой интервал.
        assertThat(waitingBefore.retryAt()).isAfter(now().minus(refundRetryInterval));

        assertThat(sweeper.cancelTimedOut()).as("timeout scan").isZero();
        assertThat(sweeper.recoverOutstandingRefunds()).as("refund recovery scan").isZero();
        // Прямой вызов компенсации по тайм-ауту не повторяет возврат.
        assertThat(saga.timeOut(waiting, "Test: direct timeout")).isFalse();
        assertThat(saga.timeOut(cancelled, "Test: direct timeout")).isFalse();
        // Повторяем проходы в другом порядке.
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
        assertThat(sweeper.cancelTimedOut()).isZero();

        // Статус, причина, платёж, время попытки и версия остаются без изменений.
        assertThat(book(waiting)).isEqualTo(waitingBefore);
        assertThat(book(cancelled)).isEqualTo(cancelledBefore);
        assertThat(outbox.findAll()).isEmpty();
    }

    // ------------------------------------------------------------------ после наступления срока

    @Test
    void aDueRefundIsRequeuedExactlyOnceAndThenThrottled() {
        UUID waiting = refundOwingOrder(OrderStatus.CANCELLATION_PENDING);
        UUID cancelled = refundOwingOrder(OrderStatus.CANCELLED);
        age(DUE_AGE, waiting, cancelled);
        Book waitingBefore = book(waiting);
        Book cancelledBefore = book(cancelled);
        OffsetDateTime beforeSweep = now();

        // Проверка тайм-аутов сохраняет заказы с ожидающим возвратом.
        assertThat(sweeper.cancelTimedOut()).as("timeout scan").isZero();
        assertThat(refundCommands(waiting)).isZero();
        assertThat(refundCommands(cancelled)).isZero();
        assertThat(book(waiting)).isEqualTo(waitingBefore);

        // Сканирование возвратов повторяет обе просроченные команды по одному разу.
        assertThat(sweeper.recoverOutstandingRefunds()).as("refund recovery scan").isEqualTo(2);
        assertRequeuedOnce(waiting, waitingBefore, beforeSweep, 1);
        assertRequeuedOnce(cancelled, cancelledBefore, beforeSweep, 1);

        // Повторные проходы внутри нового окна не создают дубликаты.
        assertThat(sweeper.cancelTimedOut()).isZero();
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
        // Прямой вызов с тем же сроком также не повторяет команду.
        OffsetDateTime dueBefore = now().minus(refundRetryInterval);
        assertThat(saga.recoverOutstandingRefund(waiting, "Test: duplicate", dueBefore)).isFalse();
        assertThat(saga.recoverOutstandingRefund(cancelled, "Test: duplicate", dueBefore)).isFalse();
        assertThat(refundCommands(waiting)).isEqualTo(1);
        assertThat(refundCommands(cancelled)).isEqualTo(1);

        // Сдвигаем время попытки на интервал назад, чтобы открыть следующее окно.
        age(DUE_AGE, waiting, cancelled);
        assertThat(sweeper.cancelTimedOut()).isZero();
        assertThat(sweeper.recoverOutstandingRefunds()).isEqualTo(2);
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
        assertThat(refundCommands(waiting)).isEqualTo(2);
        assertThat(refundCommands(cancelled)).isEqualTo(2);
    }

    @Test
    void concurrentScansRequeueADueRefundExactlyOnce() throws Exception {
        UUID waiting = refundOwingOrder(OrderStatus.CANCELLATION_PENDING);
        age(DUE_AGE, waiting);
        Book before = book(waiting);
        OffsetDateTime beforeScans = now();

        // Два прохода возвратов и один проход тайм-аутов запускаются одновременно.
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CyclicBarrier start = new CyclicBarrier(3);
            Callable<Integer> recovery = () -> {
                start.await(10, TimeUnit.SECONDS);
                return sweeper.recoverOutstandingRefunds();
            };
            Callable<Integer> timeoutScan = () -> {
                start.await(10, TimeUnit.SECONDS);
                return sweeper.cancelTimedOut();
            };
            Future<Integer> first = pool.submit(recovery);
            Future<Integer> second = pool.submit(recovery);
            Future<Integer> third = pool.submit(timeoutScan);
            assertThat(first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS))
                    .as("refunds re-queued by the two recovery scans").isEqualTo(1);
            assertThat(third.get(30, TimeUnit.SECONDS)).as("timeout scan").isZero();
        }
        finally {
            pool.shutdownNow();
        }

        assertRequeuedOnce(waiting, before, beforeScans, 1);
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
    }

    @Test
    void aSettledRefundIsNeverRequeuedAgain() {
        UUID waiting = refundOwingOrder(OrderStatus.CANCELLATION_PENDING);
        UUID cancelled = refundOwingOrder(OrderStatus.CANCELLED);
        age(DUE_AGE, waiting, cancelled);
        assertThat(sweeper.recoverOutstandingRefunds()).isEqualTo(2);

        // Ответ завершает отмену ожидающего заказа. Уже отменённый заказ сохраняет свой статус.
        for (UUID orderId : List.of(waiting, cancelled)) {
            Order order = orders.findById(orderId).orElseThrow();
            saga.paymentRefunded(UUID.randomUUID(), new PaymentRefunded(
                    orderId, order.getPaymentId(), order.getTotalAmount(), order.getCurrency(), "refund"));
            assertThat(orders.findById(orderId).orElseThrow().getRefundStatus())
                    .isEqualTo(RefundStatus.REFUNDED);
            assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }
        Book waitingSettled = book(waiting);
        Book cancelledSettled = book(cancelled);

        // Завершённые возвраты пропускаются независимо от возраста строки.
        age(DUE_AGE, waiting, cancelled);
        assertThat(sweeper.cancelTimedOut()).isZero();
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
        assertThat(saga.timeOut(waiting, "Test: late timeout")).isFalse();
        assertThat(saga.recoverOutstandingRefund(
                cancelled, "Test: late recovery", now().plus(refundRetryInterval))).isFalse();

        assertThat(refundCommands(waiting)).isEqualTo(1);
        assertThat(refundCommands(cancelled)).isEqualTo(1);
        assertThat(book(waiting).outbox()).isEqualTo(waitingSettled.outbox());
        assertThat(book(cancelled).outbox()).isEqualTo(cancelledSettled.outbox());
        // Уведомление об отмене для каждого заказа публикуется один раз.
        assertThat(book(waiting).outbox()).containsOnlyOnce("OrderCancelled");
    }

    // ------------------------------------------------------------------ отбор кандидатов

    @Test
    void refundsThatAreNotDueDoNotCrowdStuckOrdersOutOfTheTimeoutBatch() {
        // 55 ожидающих возвратов старше зависших заказов и могли бы заполнить выборку.
        List<UUID> waiting = refundOwingOrders(55, OrderStatus.CANCELLATION_PENDING);
        age(NOT_DUE_AGE, waiting.toArray(UUID[]::new));
        List<Book> waitingBefore = waiting.stream().map(this::book).toList();

        UUID stuckPending = stuckOrder(false);
        UUID stuckPaying = stuckOrder(true);
        UUID stuckPendingToo = stuckOrder(false);
        age(STUCK_AGE, stuckPending, stuckPaying, stuckPendingToo);
        // Этот заказ ещё укладывается в тайм-аут.
        UUID fresh = stuckOrder(false);
        Book freshBefore = book(fresh);

        // Ровно три обычных зависших заказа отменяются с компенсацией.
        assertThat(sweeper.cancelTimedOut()).isEqualTo(3);
        for (UUID orderId : List.of(stuckPending, stuckPaying, stuckPendingToo)) {
            Book cancelledOrder = book(orderId);
            assertThat(cancelledOrder.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(cancelledOrder.reason()).contains("did not complete within");
            assertThat(cancelledOrder.outbox()).containsOnlyOnce("ReleaseStock").containsOnlyOnce("OrderCancelled")
                    .doesNotContain(REFUND);
        }
        assertThat(book(fresh)).isEqualTo(freshBefore);

        // Ожидающие возвраты сохраняют статусы и команды.
        assertThat(waiting.stream().map(this::book).toList()).isEqualTo(waitingBefore);
        assertThat(outbox.findAll()).extracting(OutboxEvent::getAggregateId).doesNotContainAnyElementsOf(waiting);
        // У планировщика возвратов ещё нет кандидатов.
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
        assertThat(sweeper.cancelTimedOut()).isZero();
    }

    @Test
    void dueRefundsDoNotCrowdStuckOrdersOutEitherAndRecoveryStillDrainsThemInBatches() {
        List<UUID> waiting = refundOwingOrders(55, OrderStatus.CANCELLATION_PENDING);
        age(DUE_AGE, waiting.toArray(UUID[]::new));
        UUID stuckPending = stuckOrder(false);
        UUID stuckPaying = stuckOrder(true);
        age(STUCK_AGE, stuckPending, stuckPaying);

        // Просроченные возвраты обрабатываются отдельным проходом, а тайм-аут по-прежнему находит зависшие заказы.
        assertThat(sweeper.cancelTimedOut()).isEqualTo(2);
        assertThat(book(stuckPending).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(book(stuckPaying).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(waiting).allSatisfy(orderId -> assertThat(refundCommands(orderId)).isZero());

        // За проход обрабатывается до 50 возвратов. Остальные переходят в следующую пачку.
        assertThat(sweeper.recoverOutstandingRefunds()).isEqualTo(50);
        assertThat(sweeper.recoverOutstandingRefunds()).isEqualTo(5);
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
        assertThat(waiting).allSatisfy(orderId -> {
            assertThat(refundCommands(orderId)).isEqualTo(1);
            assertThat(book(orderId).status()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
        });
    }

    // ------------------------------------------------------------------ тестовые данные

    /**
     * @param status {@code CANCELLATION_PENDING} для ожидающей отмены или {@code CANCELLED} для завершённой
     */
    private UUID refundOwingOrder(OrderStatus status) {
        return refundOwingOrders(1, status).get(0);
    }

    private List<UUID> refundOwingOrders(int count, OrderStatus status) {
        List<Order> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Order order = pricedOrder();
            UUID payment = UUID.randomUUID();
            if (status == OrderStatus.CANCELLATION_PENDING) {
                order.markConfirmed(payment);
                assertThat(order.requestRefund()).isTrue();
                order.markCancellationPending(OrderSagaTransactions.CANCELLED_BY_CUSTOMER);
            }
            else {
                order.rememberPayment(payment);
                assertThat(order.requestRefund()).isTrue();
                order.markCancelled(OrderSagaTransactions.CANCELLED_BY_CUSTOMER);
            }
            batch.add(order);
        }
        orders.saveAll(batch);
        return batch.stream().map(Order::getId).toList();
    }

    /** @return заказ в {@code PENDING} или {@code PAYMENT_PENDING} */
    private UUID stuckOrder(boolean paymentPending) {
        Order order = pricedOrder();
        if (paymentPending) {
            order.markPaymentPending(UUID.randomUUID());
        }
        return orders.saveAndFlush(order).getId();
    }

    private static Order pricedOrder() {
        Order order = Order.pending(UUID.randomUUID(), "EUR", null);
        order.addLine(UUID.randomUUID(), "Widget", new BigDecimal("12.50"), 2);
        return order;
    }

    /**
     * Сдвигает время последнего перехода и попытки возврата без ожидания.
     */
    private void age(Duration age, UUID... orderIds) {
        OffsetDateTime then = now().minus(age);
        for (UUID orderId : orderIds) {
            jdbc.update("UPDATE orders SET updated_at = ?, refund_retry_at = "
                    + "CASE WHEN refund_status = 'NONE' THEN refund_retry_at ELSE ? END WHERE id = ?",
                    then, then, orderId);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ проверки и инфраструктура

    /**
     * @param orderId идентификатор заказа
     * @param expectedWrites ожидаемое число команд
     * @param notBefore нижняя граница времени попытки
     * @param before верхняя граница времени попытки
     */
    private void assertRequeuedOnce(UUID orderId, Book before, OffsetDateTime notBefore, int expectedWrites) {
        Book after = book(orderId);
        // Команда по отслеживаемому платежу и новая метка времени фиксируются вместе.
        assertThat(refundCommands(orderId)).isEqualTo(1);
        OutboxEvent command = outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .filter(row -> REFUND.equals(row.getEventType())).findFirst().orElseThrow();
        assertThat(payloadOf(command).get("payload").get("paymentId").asText())
                .isEqualTo(before.payment().toString());
        assertThat(after.retryAt()).isAfterOrEqualTo(notBefore);
        assertThat(after.version()).isEqualTo(before.version() + expectedWrites);
        // После повтора статус заказа, причина, идентификатор платежа и признак возврата прежние.
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.reason()).isEqualTo(before.reason());
        assertThat(after.payment()).isEqualTo(before.payment());
        assertThat(after.refund()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(after.outbox()).containsExactly(REFUND);
    }

    private long refundCommands(UUID orderId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .filter(row -> REFUND.equals(row.getEventType()))
                .count();
    }

    private Book book(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow();
        return new Book(order.getStatus(), order.getRefundStatus(), order.getVersion(),
                order.getRefundRetryAt(), order.getUpdatedAt(), order.getFailureReason(), order.getPaymentId(),
                outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream().map(OutboxEvent::getEventType).toList());
    }

    private JsonNode payloadOf(OutboxEvent row) {
        try {
            return parse(row.getPayload());
        }
        catch (IOException e) {
            throw new UncheckedIOException("Outbox payload is not JSON", e);
        }
    }

    /** Снимок полей заказа для сравнения до и после попытки. */
    private record Book(
            OrderStatus status,
            RefundStatus refund,
            long version,
            OffsetDateTime retryAt,
            OffsetDateTime updatedAt,
            String reason,
            UUID payment,
            List<String> outbox) {
    }
}
