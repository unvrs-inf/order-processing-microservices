package com.example.orders.inventory.service;

import com.example.orders.inventory.client.OrderStatusClient;
import com.example.orders.inventory.client.OrderStatusVerdict;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.support.error.BadRequestException;
import com.example.orders.support.tx.OptimisticRetry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Ручная сверка зависших резервов, оставшихся у отменённых заказов.
 * <ul>
 * <li>{@link OrderStatusClient} проверяет заказ через {@code GET /api/orders/{id}} с токеном администратора. Только корректный ответ для этого заказа со статусом {@code CANCELLED} или {@code REJECTED} разрешает продолжить. Другие ответы не меняют склад, резервы, отметки или outbox.</li>
 * <li>Сетевой вызов выполняется вне складской транзакции и блокировки. Каждый подтверждённый заказ обрабатывается в отдельной транзакции {@code REQUIRES_NEW} под advisory-блокировкой.</li>
 * <li>Освобождаются строки {@code RESERVED}. При наличии {@code COMMITTED} сверка отказывается от всего заказа, передавая компенсацию обычному сценарию возврата. Каждый принятый заказ получает отметку освобождения.</li>
 * <li>После {@value #LOOKUP_FAILURE_LIMIT} сбоев зависимости подряд оставшиеся заказы пропускаются.</li>
 * </ul>
 */
@Service
public class ReservationReconciliation {

    /** Максимальное число идентификаторов в запросе. */
    public static final int MAX_ORDER_IDS = 200;

    /** Число последовательных сбоев order-service, после которых остальные идентификаторы пропускаются. */
    public static final int LOOKUP_FAILURE_LIMIT = 3;

    /** Причина освобождения по умолчанию. */
    public static final String DEFAULT_REASON = "Recovered leaked reservation";

    private static final Logger log = LoggerFactory.getLogger(ReservationReconciliation.class);

    private final StockReservationTransactions transactions;
    private final OrderStatusClient orderStatus;
    private final int maxAttempts;

    ReservationReconciliation(
            StockReservationTransactions transactions,
            OrderStatusClient orderStatus,
            @Value("${inventory.reservation.max-attempts:3}") int maxAttempts) {
        this.transactions = transactions;
        this.orderStatus = orderStatus;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param orderIds не более {@value #MAX_ORDER_IDS} идентификаторов без {@code null}; дубликаты объединяются, порядок сохраняется
     * @param reason причина для журнала; пустая строка заменяется значением по умолчанию
     * @param bearerToken JWT администратора для проверки; не включается в логи
     * @return по результату на каждый уникальный идентификатор в исходном порядке
     * @throws BadRequestException если список или токен не прошёл проверку
     */
    public List<ReconciliationLine> recover(List<UUID> orderIds, String reason, String bearerToken) {
        Set<UUID> distinct = validated(orderIds);
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new BadRequestException("An administrator token is required to verify the orders' state");
        }
        String effectiveReason = reason == null || reason.isBlank()
                ? DEFAULT_REASON
                : reason.trim();
        List<ReconciliationLine> lines = new ArrayList<>(distinct.size());
        int consecutiveFailures = 0;
        for (UUID orderId : distinct) {
            if (consecutiveFailures >= LOOKUP_FAILURE_LIMIT) {
                lines.add(new ReconciliationLine(orderId, ReconciliationOutcome.SKIPPED_VERIFICATION_UNAVAILABLE));
                continue;
            }
            // Выполняем сетевой вызов до транзакции и блокировки.
            OrderStatusVerdict verdict = orderStatus.check(orderId, bearerToken);
            consecutiveFailures = verdict.isDependencyFailure() ? consecutiveFailures + 1 : 0;
            ReconciliationOutcome outcome = verdict.permitsRelease()
                    ? OptimisticRetry.execute(
                            maxAttempts, () -> transactions.reconcileRelease(orderId, effectiveReason))
                    : refusal(verdict);
            log.info("Recovery: order {} (order-service says {}) -> {}", orderId, verdict, outcome);
            lines.add(new ReconciliationLine(orderId, outcome));
        }
        return lines;
    }

    /** @param orderId заказ для проверки зависших резервов */
    public List<StockReservation> reservationsOf(UUID orderId) {
        return transactions.findByOrder(orderId);
    }

    private static Set<UUID> validated(List<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw new BadRequestException("At least one order id is required");
        }
        // LinkedHashSet сохраняет порядок идентификаторов для отчёта.
        Set<UUID> distinct = new LinkedHashSet<>();
        for (UUID orderId : orderIds) {
            if (orderId == null) {
                throw new BadRequestException("Order ids must not contain null");
            }
            distinct.add(orderId);
        }
        if (orderIds.size() > MAX_ORDER_IDS) {
            throw new BadRequestException("At most " + MAX_ORDER_IDS + " order ids per request");
        }
        return distinct;
    }

    private static ReconciliationOutcome refusal(OrderStatusVerdict verdict) {
        return switch (verdict) {
            case ACTIVE -> ReconciliationOutcome.REFUSED_ACTIVE_ORDER;
            case NOT_FOUND -> ReconciliationOutcome.REFUSED_ORDER_NOT_FOUND;
            case ACCESS_DENIED -> ReconciliationOutcome.REFUSED_ACCESS_DENIED;
            case UNVERIFIABLE -> ReconciliationOutcome.REFUSED_UNVERIFIABLE;
            case UNAVAILABLE -> ReconciliationOutcome.REFUSED_VERIFICATION_UNAVAILABLE;
            case CANCELLED, REJECTED -> throw new IllegalStateException("releasable verdict has no refusal");
        };
    }

    /**
     * @param orderId заказ, к которому относится строка
     * @param outcome результат сверки
     */
    public record ReconciliationLine(UUID orderId, ReconciliationOutcome outcome) {
    }
}
