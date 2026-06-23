package com.example.orders.order.saga;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.RefundStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Планировщик тайм-аутов Saga и повторных возвратов.
 * <p>Если шаг не ответил и с последнего перехода прошло {@code ORDER_SAGA_TIMEOUT}, заказ отменяется с компенсацией. Отсчёт от перехода даёт время заказам, которые продолжают обрабатываться.
 * <p>Вторая выборка ищет просроченные возвраты по {@code orders.saga.refund-retry-interval}, начиная с самых старых попыток. Она единственная повторяет запрос возврата. Первая выборка исключает заказы с ожидающим возвратом.
 * <p>Каждый заказ обрабатывается в отдельной транзакции. Kafka может доставить команду повторно; идемпотентность гарантирует потребитель.
 */
@Component
@ConditionalOnProperty(name = "orders.saga.timeout-sweeper-enabled", havingValue = "true", matchIfMissing = true)
class OrderSagaTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaTimeoutSweeper.class);

    private final OrderRepository orders;
    private final OrderSaga saga;
    private final Duration timeout;
    private final Duration refundRetryInterval;

    OrderSagaTimeoutSweeper(
            OrderRepository orders,
            OrderSaga saga,
            @Value("${orders.saga.timeout:PT2M}") Duration timeout,
            @Value("${orders.saga.refund-retry-interval:PT30S}") Duration refundRetryInterval) {
        this.orders = orders;
        this.saga = saga;
        this.timeout = timeout;
        this.refundRetryInterval = refundRetryInterval;
    }

    @Scheduled(
            fixedDelayString = "${orders.saga.sweep-interval-ms:15000}",
            initialDelayString = "${orders.saga.sweep-interval-ms:15000}")
    void sweep() {
        int cancelled = cancelTimedOut();
        int recovered = recoverOutstandingRefunds();
        if (cancelled + recovered > 0) {
            log.info("Sweep finished: {} order(s) cancelled, {} refund(s) re-queued",
                    cancelled, recovered);
        }
    }

    /**
     * Отменяет заказы с истёкшим тайм-аутом. Для возвратов со статусом {@code REQUESTED} действует {@link #recoverOutstandingRefunds()}.
     * <p>Выборка только читает данные. Каждая отмена выполняется в отдельной транзакции.
     * @return число отменённых заказов
     */
    @Transactional(readOnly = true)
    public int cancelTimedOut() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(timeout);
        List<Order> stuck = orders.findTop50ByStatusInAndRefundStatusNotAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                OrderStatus.UNFINISHED, RefundStatus.REQUESTED, cutoff);
        if (stuck.isEmpty()) {
            return 0;
        }
        log.warn("Saga timeout sweep found {} order(s) with no progress since {}", stuck.size(), cutoff);
        int cancelled = 0;
        for (Order order : stuck) {
            String reason = "The order was cancelled because step '%s' did not complete within %s"
                    .formatted(order.getStatus(), timeout);
            if (saga.timeOut(order.getId(), reason)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     * Повторяет просроченные возвраты, в том числе для уже отменённых заказов. Статус заказа и уведомление об отмене не меняются.
     * <p>Метка попытки и команда сохраняются в одной транзакции на заказ.
     * @return число повторно запрошенных возвратов
     */
    @Transactional(readOnly = true)
    public int recoverOutstandingRefunds() {
        OffsetDateTime dueBefore = OffsetDateTime.now(ZoneOffset.UTC).minus(refundRetryInterval);
        List<Order> owed = orders.findTop50ByRefundStatusAndStatusInAndRefundRetryAtBeforeOrderByRefundRetryAtAsc(
                RefundStatus.REQUESTED,
                Set.of(OrderStatus.CANCELLED, OrderStatus.CANCELLATION_PENDING),
                dueBefore);
        if (owed.isEmpty()) {
            return 0;
        }
        log.warn("Refund recovery sweep found {} refund(s) unanswered since before {}",
                owed.size(), dueBefore);
        int recovered = 0;
        for (Order order : owed) {
            String reason = ("The refund was commanded again because the payment-service did not"
                    + " confirm the first command within %s").formatted(refundRetryInterval);
            if (saga.recoverOutstandingRefund(order.getId(), reason, dueBefore)) {
                recovered++;
            }
        }
        return recovered;
    }
}
