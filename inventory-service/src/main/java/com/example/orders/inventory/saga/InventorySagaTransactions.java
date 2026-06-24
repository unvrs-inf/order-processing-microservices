package com.example.orders.inventory.saga;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.ReleaseStock;
import com.example.orders.common.events.StockRejected;
import com.example.orders.common.events.StockReserved;
import com.example.orders.common.events.Topics;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.inventory.domain.StockReservationRepository;
import com.example.orders.inventory.service.ReservationResult;
import com.example.orders.inventory.service.ReservationTransition;
import com.example.orders.inventory.service.StockReservationTransactions;
import com.example.orders.support.idempotency.ProcessedEvents;
import com.example.orders.support.outbox.OutboxWriter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Складская часть Saga. Отметка {@code processed_events}, изменение остатков и ответ outbox записываются в одной транзакции на сообщение.
 * <p>Резерв создаётся после {@code OrderCreated}. Команды {@code ReleaseStock} и {@code CommitStock} отправляет оркестратор.
 * <p>События и команды приходят из разных топиков Kafka и могут поменяться местами. Освобождение сохраняет {@code order_release_intents}, а резервирование сначала проверяет эту таблицу. {@link com.example.orders.inventory.service.SagaOrderLocks#lockOrder} упорядочивает операции по заказу.
 * <p>Блокировку и признак освобождения применяет {@link StockReservationTransactions}. Обработчики добавляют дедупликацию и запись ответа. Оркестратор повторяет команду освобождения при позднем резерве.
 */
@Component
public class InventorySagaTransactions {

    private static final Logger log = LoggerFactory.getLogger(InventorySagaTransactions.class);

    /** Имя потребителя событий заказа в {@code processed_events}. */
    public static final String ORDER_CONSUMER = "inventory.order-events";

    /** Имя потребителя команд в {@code processed_events}. */
    public static final String COMMAND_CONSUMER = "inventory.commands";

    private final StockReservationTransactions reservations;
    private final StockReservationRepository reservationRows;
    private final OutboxWriter outbox;
    private final ProcessedEvents processedEvents;

    InventorySagaTransactions(
            StockReservationTransactions reservations,
            StockReservationRepository reservationRows,
            OutboxWriter outbox,
            ProcessedEvents processedEvents) {
        this.reservations = reservations;
        this.reservationRows = reservationRows;
        this.outbox = outbox;
        this.processedEvents = processedEvents;
    }

    /**
     * Резервирует все строки нового заказа.
     * @param eventId идентификатор {@code OrderCreated}
     * @param event тело события
     */
    @Transactional
    public void onOrderCreated(UUID eventId, OrderCreated event) {
        if (!processedEvents.markProcessed(eventId, ORDER_CONSUMER)) {
            return;
        }
        if (event.orderId() == null || event.items() == null || event.items().isEmpty()) {
            // У события корректная оболочка, но в заказе нечего резервировать. Возвращаем отказ оркестратору.
            log.warn("OrderCreated {} has no usable items, rejecting", event.orderId());
            reject(event.orderId(), "The order contains no items", List.of());
            return;
        }

        // Операция сериализуется с ReleaseStock и CommitStock и проверяет зарегистрированное освобождение.
        ReservationResult result = reservations.reserveWithin(event.orderId(), event.items());
        if (result.outcome() == ReservationResult.Outcome.ALREADY_RELEASED) {
            // Отменённый заказ может впервые прийти после ReleaseStock. Запись об освобождении блокирует резервирование и при повторной доставке OrderCreated.
            log.warn("OrderCreated for order {} whose release was already processed: rejecting stock",
                    event.orderId());
            reject(event.orderId(), "The order was cancelled before its stock could be reserved", List.of());
            return;
        }
        if (result.isHoldingStock()) {
            UUID reservationId = representativeReservationId(event.orderId());
            outbox.record(event.orderId(), StockReserved.class.getSimpleName(), Topics.INVENTORY_EVENTS,
                    new StockReserved(event.orderId(), reservationId, event.items()));
            log.info("Reserved stock for order {} (reservation {}, outcome {})",
                    event.orderId(), reservationId, result.outcome());
            return;
        }
        String reason = describe(result);
        reject(event.orderId(), reason, result.shortages().stream()
                .map(shortage -> shortage.sku() == null ? String.valueOf(shortage.productId()) : shortage.sku())
                .toList());
        log.info("Rejected stock for order {}: {}", event.orderId(), reason);
    }

    /**
     * Подтверждает резерв после оплаты.
     * @param eventId идентификатор {@code CommitStock}
     * @param command тело команды
     */
    @Transactional
    public void onCommitStock(UUID eventId, CommitStock command) {
        if (!processedEvents.markProcessed(eventId, COMMAND_CONSUMER)) {
            return;
        }
        if (command.orderId() == null) {
            log.warn("Ignoring CommitStock without an order id");
            return;
        }
        // Блокировка сериализует CommitStock и ReleaseStock. Освобождённый резерв не переводится обратно в продажу.
        ReservationTransition transition = reservations.commitWithin(command.orderId());
        log.info("CommitStock for order {}: {}", command.orderId(), transition);
    }

    /**
     * Освобождает остатки после отмены. Даже при отсутствии резерва записывает {@code order_release_intents}, чтобы последующий {@code OrderCreated} не зарезервировал товар.
     * @param eventId идентификатор {@code ReleaseStock}
     * @param command тело команды
     */
    @Transactional
    public void onReleaseStock(UUID eventId, ReleaseStock command) {
        if (!processedEvents.markProcessed(eventId, COMMAND_CONSUMER)) {
            return;
        }
        if (command.orderId() == null) {
            log.warn("Ignoring ReleaseStock without an order id");
            return;
        }
        // Под блокировкой одновременно освобождаем строки и записываем запрос. ON CONFLICT сохраняет первую причину при повторах.
        ReservationTransition transition = reservations.releaseWithin(command.orderId(), command.reason());
        if (transition == ReservationTransition.UNKNOWN_ORDER) {
            // Оркестратор освобождает резерв при отмене, даже если ещё не получил его идентификатор. Запись остановит позднее резервирование.
            log.info("ReleaseStock for order {} ({}): {} — release intent recorded",
                    command.orderId(), command.reason(), transition);
            return;
        }
        log.info("ReleaseStock for order {} ({}): {}",
                command.orderId(), command.reason(), transition);
    }

    private void reject(UUID orderId, String reason, List<String> unavailableSkus) {
        outbox.record(orderId, StockRejected.class.getSimpleName(), Topics.INVENTORY_EVENTS,
                new StockRejected(orderId, reason, unavailableSkus));
    }

    /**
     * @param orderId заказ с зарезервированными остатками
     * @return идентификатор первой строки резерва для {@code StockReserved}. Следующие команды обращаются по идентификатору заказа
     */
    private UUID representativeReservationId(UUID orderId) {
        List<StockReservation> rows = reservationRows.findByOrderIdOrderByCreatedAtAsc(orderId);
        return rows.isEmpty() ? null : rows.getFirst().getId();
    }

    private static String describe(ReservationResult result) {
        if (result.shortages().isEmpty()) {
            return "Stock could not be reserved";
        }
        return result.shortages().stream()
                .map(shortage -> switch (shortage.reason()) {
                    case PRODUCT_NOT_FOUND -> "product %s is unknown".formatted(shortage.productId());
                    case INSUFFICIENT_STOCK -> "%s: requested %d, available %d"
                            .formatted(shortage.sku(), shortage.requested(), shortage.available());
                })
                .collect(Collectors.joining("; ", "Insufficient stock (", ")"));
    }
}
