package com.example.orders.order.saga;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.OrderCancelled;
import com.example.orders.common.events.OrderConfirmed;
import com.example.orders.common.events.OrderRejected;
import com.example.orders.common.events.PaymentCompleted;
import com.example.orders.common.events.PaymentFailed;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.ProcessPayment;
import com.example.orders.common.events.RefundPayment;
import com.example.orders.common.events.ReleaseStock;
import com.example.orders.common.events.StockRejected;
import com.example.orders.common.events.StockReserved;
import com.example.orders.common.events.Topics;
import com.example.orders.order.api.OrderResponse;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.RefundStatus;
import com.example.orders.support.error.ConflictException;
import com.example.orders.support.error.NotFoundException;
import com.example.orders.support.idempotency.ProcessedEvents;
import com.example.orders.support.outbox.OutboxWriter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Изменения Saga выполняются в отдельной транзакции на метод.
 * <ol>
 * <li>{@code processedEvents.markProcessed} отмечает полученное событие. Откат удаляет отметку, и повторная доставка сможет завершить обработку.</li>
 * <li>Проверяется текущий статус. Неожиданное событие записывается в WARN. Поздние резервы или платежи компенсируются через {@link #onStockReserved} и {@link #compensateLatePayment}.</li>
 * <li>Новый статус и сообщения outbox сохраняются атомарно.</li>
 * </ol>
 * <p>{@code orders.refund_status} учитывает возврат отдельно от статуса заказа. Команда {@code RefundPayment} фиксируется вместе с признаком {@code REQUESTED}. Повторное событие с тем же платежом найдёт этот признак. При конфликте версий {@link OrderSaga} открывает новую транзакцию и заново читает заказ.
 * <p>{@code orders.refund_retry_at} хранит время последней попытки для {@link OrderSagaTimeoutSweeper}. Отдельный bean позволяет начать новую транзакцию при повторе.
 */
@Component
public class OrderSagaTransactions {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaTransactions.class);

    /** Имя складского потребителя в {@code processed_events}. */
    public static final String INVENTORY_CONSUMER = "order.inventory-events";

    /** Имя платёжного потребителя в {@code processed_events}. */
    public static final String PAYMENT_CONSUMER = "order.payment-events";

    /** Причина отмены по запросу клиента. */
    public static final String CANCELLED_BY_CUSTOMER = "Cancelled by the customer";

    private final OrderRepository orders;
    private final OutboxWriter outbox;
    private final ProcessedEvents processedEvents;

    OrderSagaTransactions(OrderRepository orders, OutboxWriter outbox, ProcessedEvents processedEvents) {
        this.orders = orders;
        this.outbox = outbox;
        this.processedEvents = processedEvents;
    }

    // ------------------------------------------------------------------ ответы склада

    /**
     * Сохраняет резерв и запрашивает оплату.
     * <p>Если заказ уже закрыт, сохраняет сведения о позднем резерве и повторяет {@code ReleaseStock}. inventory-service также фиксирует запрос на освобождение заранее, поэтому последующий {@code OrderCreated} обычно не создаст резерв.
     * @param eventId идентификатор {@code StockReserved}
     * @param event тело события
     */
    @Transactional
    public void onStockReserved(UUID eventId, StockReserved event) {
        if (!processedEvents.markProcessed(eventId, INVENTORY_CONSUMER)) {
            return;
        }
        Order order = find(event.orderId(), "StockReserved");
        if (order == null) {
            return;
        }
        if (order.getStatus() == OrderStatus.PENDING) {
            order.markPaymentPending(event.reservationId());
            orders.saveAndFlush(order);
            outbox.record(order.getId(), ProcessPayment.class.getSimpleName(), Topics.PAYMENT_COMMANDS,
                    new ProcessPayment(
                            order.getId(), order.getUserId(), order.getTotalAmount(), order.getCurrency()));
            log.info("Order {} has stock reserved ({}), asking for payment of {} {}",
                    order.getId(), event.reservationId(), order.getTotalAmount(), order.getCurrency());
            return;
        }
        if (order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.CANCELLATION_PENDING
                || order.getStatus() == OrderStatus.REJECTED) {
            // Заказ уже закрыт. Сохраняем резерв для компенсации и повторяем ReleaseStock. Склад обрабатывает её идемпотентно.
            // REJECTED включён, поскольку при штатном отказе освобождение не отправлялось. Поздний резерв освобождается с сохранением REJECTED.
            if (order.getReservationId() == null) {
                order.rememberReservation(event.reservationId());
                orders.saveAndFlush(order);
                releaseStock(order, cancellationReason(order));
                log.warn("Late StockReserved for {} order {}: reservation {} re-released",
                        order.getStatus(), order.getId(), event.reservationId());
            }
            else {
                // Освобождение уже было отправлено при отмене. Этот резерв уже известен.
                ignored("StockReserved", order);
            }
            return;
        }
        // Для незавершённого заказа учитываем повторный ответ. Финальное состояние сохраняется.
        if (order.getReservationId() == null && !order.getStatus().isFinal()) {
            order.rememberReservation(event.reservationId());
            orders.saveAndFlush(order);
        }
        ignored("StockReserved", order);
    }

    /**
     * Склад отклонил резерв. Заказ отклоняется без компенсации.
     * @param eventId идентификатор {@code StockRejected}
     * @param event тело события
     */
    @Transactional
    public void onStockRejected(UUID eventId, StockRejected event) {
        if (!processedEvents.markProcessed(eventId, INVENTORY_CONSUMER)) {
            return;
        }
        Order order = find(event.orderId(), "StockRejected");
        if (order == null) {
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            ignored("StockRejected", order);
            return;
        }
        String reason = event.reason() == null ? "Stock could not be reserved" : event.reason();
        order.markRejected(reason);
        orders.saveAndFlush(order);
        outbox.record(order.getId(), OrderRejected.class.getSimpleName(), Topics.ORDER_EVENTS,
                new OrderRejected(order.getId(), order.getOrderNumber(), order.getUserId(), reason));
        log.info("Order {} rejected: {}", order.getId(), reason);
    }

    // ------------------------------------------------------------------ ответы платёжного сервиса

    /**
     * Подтверждает оплату и превращает резерв в продажу. При позднем платеже запускает {@link #compensateLatePayment}.
     * @param eventId идентификатор {@code PaymentCompleted}
     * @param event тело события
     */
    @Transactional
    public void onPaymentCompleted(UUID eventId, PaymentCompleted event) {
        if (!processedEvents.markProcessed(eventId, PAYMENT_CONSUMER)) {
            return;
        }
        Order order = find(event.orderId(), "PaymentCompleted");
        if (order == null) {
            return;
        }
        if (OrderStatus.STOCK_HELD.contains(order.getStatus())) {
            order.markConfirmed(event.paymentId());
            orders.saveAndFlush(order);
            outbox.record(order.getId(), OrderConfirmed.class.getSimpleName(), Topics.ORDER_EVENTS,
                    new OrderConfirmed(order.getId(), order.getOrderNumber(), order.getUserId(),
                            order.getTotalAmount(), order.getCurrency()));
            outbox.record(order.getId(), CommitStock.class.getSimpleName(), Topics.INVENTORY_COMMANDS,
                    new CommitStock(order.getId()));
            log.info("Order {} confirmed, payment {}", order.getId(), event.paymentId());
            return;
        }
        compensateLatePayment(order, event);
    }

    /**
     * Отменяет заказ и освобождает резерв после отказа в оплате.
     * @param eventId идентификатор {@code PaymentFailed}
     * @param event тело события
     */
    @Transactional
    public void onPaymentFailed(UUID eventId, PaymentFailed event) {
        if (!processedEvents.markProcessed(eventId, PAYMENT_CONSUMER)) {
            return;
        }
        Order order = find(event.orderId(), "PaymentFailed");
        if (order == null) {
            return;
        }
        if (!OrderStatus.STOCK_HELD.contains(order.getStatus())) {
            // Отклонённый или уже отменённый заказ больше не ожидает результат оплаты.
            ignored("PaymentFailed", order);
            return;
        }
        String reason = event.reason() == null ? "Payment failed" : event.reason();
        // Освобождение и новый статус фиксируются одновременно.
        releaseStock(order, reason);
        order.markCancelled(reason);
        orders.saveAndFlush(order);
        publishCancelled(order, reason);
        log.info("Order {} cancelled after a failed payment: {}", order.getId(), reason);
    }

    /**
     * Завершает отмену после возврата. Если заказ уже отменён, обновляет только {@code refund_status}. Повторный ответ найдёт {@code REFUNDED} и не отправит {@code OrderCancelled} ещё раз.
     * @param eventId идентификатор {@code PaymentRefunded}
     * @param event тело события
     */
    @Transactional
    public void onPaymentRefunded(UUID eventId, PaymentRefunded event) {
        if (!processedEvents.markProcessed(eventId, PAYMENT_CONSUMER)) {
            return;
        }
        Order order = find(event.orderId(), "PaymentRefunded");
        if (order == null) {
            return;
        }
        if (order.getRefundStatus() == RefundStatus.REFUNDED) {
            ignored("PaymentRefunded (refund already settled)", order);
            return;
        }
        if (guardTrackedPayment(order, event.paymentId(), "PaymentRefunded")) {
            // Ответ относится к чужому платежу. Состояние заказа и идентификатор платежа не меняются.
            return;
        }
        switch (order.getStatus()) {
            case CANCELLATION_PENDING -> {
                String reason = order.getFailureReason() == null
                        ? CANCELLED_BY_CUSTOMER
                        : order.getFailureReason();
                order.markRefunded();
                order.markCancelled(reason);
                orders.saveAndFlush(order);
                publishCancelled(order, reason);
                log.info("Order {} cancelled, refund {} completed", order.getId(), event.paymentId());
            }
            case CANCELLED -> {
                if (order.getRefundStatus() == RefundStatus.REQUESTED) {
                    // Отмена уже сообщена. Закрываем только отслеживаемый возврат.
                    order.markRefunded();
                    orders.saveAndFlush(order);
                    log.info("Refund {} for already-cancelled order {} completed",
                            event.paymentId(), order.getId());
                }
                else {
                    // Saga не запрашивала этот возврат. Чужое или повторное событие не меняет признак.
                    ignored("PaymentRefunded (no tracked refund)", order);
                }
            }
            default -> {
                // Заказ в CONFIRMED, PENDING или REJECTED не ожидает отслеживаемого возврата.
                ignored("PaymentRefunded", order);
            }
        }
    }

    // ------------------------------------------------------------------ отмена клиентом

    /**
     * Отменяет заказ по запросу клиента или администратора.
     * @param orderId заказ для отмены
     * @param callerId идентификатор пользователя
     * @param admin наличие роли {@code ADMIN}
     * @return заказ после начала отмены
     * @throws NotFoundException если заказ отсутствует или принадлежит другому пользователю
     * @throws ConflictException если заказ уже завершён или отменяется
     */
    @Transactional
    public OrderResponse cancelOnRequest(UUID orderId, UUID callerId, boolean admin) {
        Order order = orders.findWithItemsById(orderId)
                .filter(candidate -> admin || candidate.getUserId().equals(callerId))
                .orElseThrow(() -> NotFoundException.of("Order", orderId));

        switch (order.getStatus()) {
            case PENDING, STOCK_RESERVED, PAYMENT_PENDING -> {
                // Отправляем освобождение в любом случае. Если склад ещё не создал резерв, он запишет запрос на освобождение. Если резерв уже есть, склад его освободит. Поздний ответ оплаченного заказа запустит возврат отдельно.
                releaseStock(order, CANCELLED_BY_CUSTOMER);
                refundPayment(order, CANCELLED_BY_CUSTOMER);
                order.markCancelled(CANCELLED_BY_CUSTOMER);
                orders.saveAndFlush(order);
                publishCancelled(order, CANCELLED_BY_CUSTOMER);
                log.info("Order {} cancelled on request while in flight", order.getId());
            }
            case CONFIRMED -> {
                refundPayment(order, CANCELLED_BY_CUSTOMER);
                releaseStock(order, CANCELLED_BY_CUSTOMER);
                if (order.getRefundStatus() == RefundStatus.REQUESTED) {
                    order.markCancellationPending(CANCELLED_BY_CUSTOMER);
                    orders.saveAndFlush(order);
                    log.info("Order {} is being cancelled, waiting for the refund of payment {}",
                            order.getId(), order.getPaymentId());
                }
                else {
                    // Платёж для заказа не был записан, поэтому ожидание не требуется.
                    order.markCancelled(CANCELLED_BY_CUSTOMER);
                    orders.saveAndFlush(order);
                    publishCancelled(order, CANCELLED_BY_CUSTOMER);
                }
            }
            case CANCELLATION_PENDING -> throw new ConflictException(
                    "Order is already being cancelled, waiting for the refund to complete");
            case CANCELLED -> throw new ConflictException("Order is already cancelled");
            case REJECTED -> throw new ConflictException("Order was rejected and cannot be cancelled");
            default -> throw new ConflictException("Order cannot be cancelled in state " + order.getStatus());
        }
        return OrderResponse.of(orders.findWithItemsById(orderId).orElseThrow());
    }

    // ------------------------------------------------------------------ тайм-аут Saga

    /**
     * Отменяет зависший заказ и компенсирует выполненные операции. Возврат {@code REQUESTED} обрабатывает {@link #recoverOutstandingRefund} по собственному таймеру. Такой заказ планировщик не отменяет повторно и сохраняет outbox без изменений.
     * @param orderId заказ с истёкшим тайм-аутом
     * @param reason причина для клиента
     * @return {@code true}, если заказ был изменён
     */
    @Transactional
    public boolean timeOut(UUID orderId, String reason) {
        Optional<Order> found = orders.findById(orderId);
        if (found.isEmpty()) {
            return false;
        }
        Order order = found.get();
        if (order.getStatus().isFinal()) {
            // Заказ завершился между выборкой и транзакцией. Незавершённый возврат проверит {@link #recoverOutstandingRefund}.
            return false;
        }
        if (order.getRefundStatus() == RefundStatus.REQUESTED) {
            // Заказ ждёт возврат. Проверка нужна для прямого вызова и гонки между выборкой и запуском транзакции.
            log.debug("Order {} is waiting for the refund of payment {}: not a timeout, left to refund recovery",
                    order.getId(), order.getPaymentId());
            return false;
        }
        releaseStock(order, reason);
        refundPayment(order, reason);
        order.markCancelled(reason);
        orders.saveAndFlush(order);
        publishCancelled(order, reason);
        log.warn("Order {} timed out in the saga and was cancelled: {}", order.getId(), reason);
        return true;
    }

    /**
     * Повторяет команду для {@code CANCELLED} или {@code CANCELLATION_PENDING} при наличии платежа и признака {@code REQUESTED}. Внутри транзакции ещё раз проверяет срок последней попытки, чтобы два планировщика не отправили команды подряд.
     * <p>Команда сохраняется вместе с новой меткой попытки. Статус заказа, причина и {@code OrderCancelled} остаются прежними.
     * @param orderId заказ для восстановления
     * @param reason причина в повторной команде
     * @param dueBefore предельное время последней попытки
     * @return признак повторной отправки команды возврата
     */
    @Transactional
    public boolean recoverOutstandingRefund(UUID orderId, String reason, OffsetDateTime dueBefore) {
        if (orderId == null) {
            return false;
        }
        Order order = orders.findById(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        if (order.getRefundStatus() != RefundStatus.REQUESTED) {
            // Завершённый возврат повторять не требуется.
            return false;
        }
        if (order.getStatus() != OrderStatus.CANCELLED
                && order.getStatus() != OrderStatus.CANCELLATION_PENDING) {
            // Эта операция поддерживает только состояния, в которых возврат остался последним шагом.
            return false;
        }
        if (order.getPaymentId() == null) {
            // Без идентификатора платежа команду возврата сформировать нельзя.
            return false;
        }
        OffsetDateTime lastAttempt = order.getRefundRetryAt();
        if (lastAttempt != null && lastAttempt.isAfter(dueBefore)) {
            // Недавняя попытка уже записана. Ждём окончания интервала.
            return false;
        }
        retryRefund(order, reason);
        order.bookRefundRetry(OffsetDateTime.now(ZoneOffset.UTC));
        orders.saveAndFlush(order);
        log.warn("Refund of payment {} for {} order {} re-queued (last attempt {})",
                order.getPaymentId(), order.getStatus(), order.getId(), lastAttempt);
        return true;
    }

    // ------------------------------------------------------------------ вспомогательные операции

    /**
     * Компенсирует платёж, завершившийся после отмены или тайм-аута. {@code refund_status} отслеживает его до финального ответа.
     * <p>Повторное событие найдёт {@code REQUESTED} или {@code REFUNDED}, при необходимости после повтора транзакции. Платёжный сервис также выполняет возврат один раз на заказ.
     */
    private void compensateLatePayment(Order order, PaymentCompleted event) {
        if (order.getStatus() != OrderStatus.CANCELLED
                && order.getStatus() != OrderStatus.CANCELLATION_PENDING) {
            // CONFIRMED уже оплачен. Для REJECTED команда оплаты не должна была появиться. Событие не меняет эти состояния.
            ignored("PaymentCompleted", order);
            return;
        }
        if (guardTrackedPayment(order, event.paymentId(), "PaymentCompleted")) {
            return;
        }
        switch (order.getRefundStatus()) {
            case REFUNDED -> {
                // Возврат этого платежа уже завершён.
                ignored("PaymentCompleted (refund already settled)", order);
            }
            case REQUESTED -> {
                // Команда возврата уже была отправлена при отмене или обработке первого ответа.
                log.info("Late PaymentCompleted for order {}: refund of payment {} is already requested",
                        order.getId(), event.paymentId());
            }
            case NONE -> {
                // Первый ответ об оплате. Записываем платёж и команду возврата атомарно.
                order.rememberPayment(event.paymentId());
                order.requestRefund();
                orders.saveAndFlush(order);
                outbox.record(order.getId(), RefundPayment.class.getSimpleName(), Topics.PAYMENT_COMMANDS,
                        new RefundPayment(order.getId(), event.paymentId(), order.getTotalAmount(),
                                order.getCurrency(), "The order was already cancelled when its payment completed"));
                log.warn("Late PaymentCompleted for {} order {}: refund of payment {} requested",
                        order.getStatus() == OrderStatus.CANCELLATION_PENDING
                                ? "a cancelling" : "a cancelled", order.getId(), event.paymentId());
            }
        }
    }

    /**
     * @param order заказ из события
     * @param paymentId платёж из события
     * @param eventName имя события для лога
     * @return {@code true}, если платёж не совпадает с отслеживаемым. В payment-service действует {@code UNIQUE(order_id)}
     */
    private boolean guardTrackedPayment(Order order, UUID paymentId, String eventName) {
        if (order.getPaymentId() != null && paymentId != null
                && !order.getPaymentId().equals(paymentId)) {
            log.warn("Ignoring {} naming payment {} for order {} which tracks payment {}",
                    eventName, paymentId, order.getId(), order.getPaymentId());
            return true;
        }
        return false;
    }

    private Order find(UUID orderId, String eventName) {
        if (orderId == null) {
            log.warn("Ignoring {} without an order id", eventName);
            return null;
        }
        Order order = orders.findById(orderId).orElse(null);
        if (order == null) {
            // Неизвестный заказ означает удалённую запись или событие с другого стенда.
            log.warn("Ignoring {} for unknown order {}", eventName, orderId);
        }
        return order;
    }

    private static void ignored(String eventName, Order order) {
        log.warn("Ignoring {} for order {}: unexpected in state {} (refund {})",
                eventName, order.getId(), order.getStatus(), order.getRefundStatus());
    }

    private void releaseStock(Order order, String reason) {
        outbox.record(order.getId(), ReleaseStock.class.getSimpleName(), Topics.INVENTORY_COMMANDS,
                // Склад находит резервы по идентификатору заказа. Список строк здесь не нужен.
                new ReleaseStock(order.getId(), order.getReservationId(), reason, List.of()));
    }

    /**
     * Запрашивает возврат записанного платежа один раз. Команда и признак из {@link Order#requestRefund()} фиксируются вместе.
     * @param order заказ для возврата
     * @param reason причина для клиента
     */
    private void refundPayment(Order order, String reason) {
        if (order.getPaymentId() == null) {
            // Оплаты ещё не было. Если она завершится после отмены, возврат обработает {@link #compensateLatePayment}.
            return;
        }
        if (!order.requestRefund()) {
            // Возврат уже запрошен. Ответ ещё может быть в пути.
            log.info("Refund of payment {} for order {} is already requested, not queueing it again",
                    order.getPaymentId(), order.getId());
            return;
        }
        outbox.record(order.getId(), RefundPayment.class.getSimpleName(), Topics.PAYMENT_COMMANDS,
                new RefundPayment(order.getId(), order.getPaymentId(), order.getTotalAmount(),
                        order.getCurrency(), reason));
    }

    /**
     * Повторяет не завершившийся запрос через {@link #recoverOutstandingRefund}. Этот путь проверяет интервал и записывает попытку в той же транзакции.
     */
    private void retryRefund(Order order, String reason) {
        outbox.record(order.getId(), RefundPayment.class.getSimpleName(), Topics.PAYMENT_COMMANDS,
                new RefundPayment(order.getId(), order.getPaymentId(), order.getTotalAmount(),
                        order.getCurrency(), reason));
    }

    /** @param order заказ, резерв которого освобождается через {@code releaseStock} */
    private static String cancellationReason(Order order) {
        return order.getFailureReason() == null ? CANCELLED_BY_CUSTOMER : order.getFailureReason();
    }

    private void publishCancelled(Order order, String reason) {
        outbox.record(order.getId(), OrderCancelled.class.getSimpleName(), Topics.ORDER_EVENTS,
                new OrderCancelled(order.getId(), order.getOrderNumber(), order.getUserId(), reason));
    }
}
