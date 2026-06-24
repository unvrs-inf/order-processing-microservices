package com.example.orders.inventory.service;

import com.example.orders.common.events.OrderItem;
import com.example.orders.inventory.domain.OrderReleaseIntentRepository;
import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ProductRepository;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.inventory.domain.StockReservationRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Складские операции в транзакциях. {@link StockReservationService} при конфликте версий повторяет вызов отдельного bean и открывает новую транзакцию.
 * <p>Все пути берут {@link SagaOrderLocks} до чтения строк. Освобождение пишет {@code order_release_intents} в той же транзакции, а резервирование проверяет таблицу до изменений.
 */
@Component
public class StockReservationTransactions {

    private static final Logger log = LoggerFactory.getLogger(StockReservationTransactions.class);

    /** Причина по умолчанию для команды освобождения. */
    static final String DEFAULT_RELEASE_REASON = "Stock released";

    /** Длина столбца {@code order_release_intents.reason}. */
    private static final int MAX_REASON_LENGTH = 500;

    private final ProductRepository products;
    private final StockReservationRepository reservations;
    private final OrderReleaseIntentRepository releaseIntents;
    private final SagaOrderLocks sagaOrderLocks;

    StockReservationTransactions(
            ProductRepository products,
            StockReservationRepository reservations,
            OrderReleaseIntentRepository releaseIntents,
            SagaOrderLocks sagaOrderLocks) {
        this.products = products;
        this.reservations = reservations;
        this.releaseIntents = releaseIntents;
        this.sagaOrderLocks = sagaOrderLocks;
    }

    /**
     * Атомарное резервирование всех строк заказа.
     * @param orderId заказ для резервирования
     * @param items строки заказа; количества одного товара суммируются
     * @return результат; при отказе остатки и резервы не меняются
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReservationResult reserve(UUID orderId, List<OrderItem> items) {
        return doReserve(orderId, items);
    }

    /**
     * Резервирование в транзакции вызывающего кода. Потребитель {@code OrderCreated} сохраняет складские изменения и {@code StockReserved} или {@code StockRejected} атомарно.
     * @param orderId заказ для резервирования
     * @param items строки заказа
     * @return результат резервирования
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationResult reserveWithin(UUID orderId, List<OrderItem> items) {
        return doReserve(orderId, items);
    }

    /**
     * Освобождает остатки и сохраняет запрос в транзакции вызывающего кода. Запись создаётся и при отсутствии резерва, чтобы поздний {@code OrderCreated} не создал его.
     * @param orderId заказ для освобождения
     * @param reason причина первой команды. Пустое значение заменяется стандартным, длинное сокращается до длины столбца
     * @return признак изменения остатков
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationTransition releaseWithin(UUID orderId, String reason) {
        return releaseAndRecordIntent(orderId, reason);
    }

    /**
     * @param orderId заказ для подтверждения в транзакции вызывающего кода
     * @return признак изменения остатков
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationTransition commitWithin(UUID orderId) {
        return transition(orderId, ReservationStatus.COMMITTED);
    }

    /**
     * @param orderId заказ для освобождения
     * @return признак изменения остатков
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReservationTransition release(UUID orderId) {
        return releaseAndRecordIntent(orderId, null);
    }

    /**
     * @param orderId заказ для подтверждения
     * @return признак изменения остатков
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReservationTransition commit(UUID orderId) {
        return transition(orderId, ReservationStatus.COMMITTED);
    }

    private ReservationResult doReserve(UUID orderId, List<OrderItem> items) {
        // Блокируем заказ до чтения. Другие операции не смогут изменить строки до конца транзакции.
        sagaOrderLocks.lockOrder(orderId);
        if (releaseIntents.existsById(orderId)) {
            // Сначала проверяем запрос на освобождение. У отменённого заказа могут уже существовать строки RELEASED, которые нельзя считать действующим резервом.
            log.info("Order {} has a release intent, refusing to reserve stock for it", orderId);
            return ReservationResult.alreadyReleased(orderId);
        }
        List<StockReservation> existing = reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
        if (!existing.isEmpty()) {
            // У заказа уже есть результат. Повторная запись продублировала бы резерв.
            log.debug("Order {} already has {} reservation rows, nothing to do", orderId, existing.size());
            return ReservationResult.alreadyReserved(orderId);
        }

        Map<UUID, Integer> wanted = quantitiesByProduct(items);
        Map<UUID, Product> locked = products.lockAllByIdIn(wanted.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        // Проверяем все строки до изменений. При дефиците весь заказ отклоняется.
        List<ReservationResult.Shortage> shortages = new ArrayList<>();
        for (Map.Entry<UUID, Integer> line : wanted.entrySet()) {
            Product product = locked.get(line.getKey());
            if (product == null) {
                shortages.add(new ReservationResult.Shortage(
                        line.getKey(), null, ReservationResult.Reason.PRODUCT_NOT_FOUND, line.getValue(), 0));
            }
            else if (product.getAvailableQuantity() < line.getValue()) {
                shortages.add(new ReservationResult.Shortage(
                        product.getId(), product.getSku(), ReservationResult.Reason.INSUFFICIENT_STOCK,
                        line.getValue(), product.getAvailableQuantity()));
            }
        }
        if (!shortages.isEmpty()) {
            log.info("Rejecting reservation for order {}: {}", orderId, shortages);
            return ReservationResult.rejected(orderId, shortages);
        }

        for (Map.Entry<UUID, Integer> line : wanted.entrySet()) {
            Product product = locked.get(line.getKey());
            if (!product.reserve(line.getValue())) {
                // Проверка уже выполнена под теми же блокировками. При расхождении операция должна завершиться ошибкой.
                throw new IllegalStateException(
                        "Stock vanished between check and reserve for product " + product.getId());
            }
            reservations.save(StockReservation.reserve(orderId, product.getId(), line.getValue()));
        }
        // Проверяем UNIQUE(order_id, product_id) в рамках текущей попытки.
        reservations.flush();
        products.flush();
        log.info("Reserved {} line(s) for order {}", wanted.size(), orderId);
        return ReservationResult.reserved(orderId);
    }

    /**
     * @param orderId заказ для чтения
     * @return строки резерва в порядке создания
     */
    @Transactional(readOnly = true)
    List<StockReservation> findByOrder(UUID orderId) {
        return reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
    }

    /**
     * Сверяет один заказ в отдельной транзакции под {@link SagaOrderLocks}. Перемещает строки {@code RESERVED} и записывает причину освобождения. При наличии {@code COMMITTED} сохраняет все строки.
     * <p>{@link ReservationReconciliation} вызывает этот метод после подтверждения {@code CANCELLED} или {@code REJECTED}.
     * @param orderId заказ с зависшим резервом
     * @param reason причина сверки
     * @return результат сверки заказа
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReconciliationOutcome reconcileRelease(UUID orderId, String reason) {
        // Сверка использует ту же блокировку, что и текущие команды Saga.
        sagaOrderLocks.lockOrder(orderId);
        List<StockReservation> all = reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
        if (all.isEmpty()) {
            // Резерв не найден, но подтверждённый закрытый заказ получает отметку об освобождении.
            releaseIntents.insertIfAbsent(orderId, reason);
            log.info("Reconciliation for order {}: no reservations, intent recorded", orderId);
            return ReconciliationOutcome.NO_RESERVATIONS;
        }
        if (all.stream().anyMatch(reservation -> reservation.getStatus() == ReservationStatus.COMMITTED)) {
            log.warn("Reconciliation for order {}: contains COMMITTED (sold) rows, skipping entirely",
                    orderId);
            return ReconciliationOutcome.SKIPPED_COMMITTED;
        }
        // Отметка и обновление строк фиксируются вместе. ON CONFLICT сохраняет первую причину.
        releaseIntents.insertIfAbsent(orderId, reason);
        List<StockReservation> held = all.stream()
                .filter(reservation -> reservation.getStatus() == ReservationStatus.RESERVED)
                .toList();
        if (held.isEmpty()) {
            log.info("Reconciliation for order {}: everything already released", orderId);
            return ReconciliationOutcome.ALREADY_RELEASED;
        }
        Map<UUID, Product> locked = products.lockAllByIdIn(
                        held.stream().map(StockReservation::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (StockReservation reservation : held) {
            Product product = locked.get(reservation.getProductId());
            if (product == null) {
                throw new IllegalStateException(
                        "Reservation " + reservation.getId() + " points at a missing product");
            }
            // Сначала меняем остатки по текущему статусу строки, затем меняем статус.
            product.releaseReserved(reservation.getQuantity());
            reservation.release();
        }
        reservations.flush();
        products.flush();
        log.info("Reconciliation for order {}: released {} leaked reservation row(s)",
                orderId, held.size());
        return ReconciliationOutcome.RELEASED;
    }

    /**
     * Переводит строки в {@code RELEASED} или {@code COMMITTED}.
     * <p>Освобождение возвращает единицы из {@code reserved} для {@code RESERVED}. Для уже проданных {@code COMMITTED} единицы возвращаются напрямую в {@code available}. Подтверждаться могут только {@code RESERVED}.
     * <p>Строки в целевом состоянии пропускаются при повторной доставке.
     */
    private ReservationTransition transition(UUID orderId, ReservationStatus target) {
        // Чтение под блокировкой защищает от конкурентного перемещения тех же единиц.
        sagaOrderLocks.lockOrder(orderId);
        List<StockReservation> all = reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
        if (all.isEmpty()) {
            return ReservationTransition.UNKNOWN_ORDER;
        }
        List<StockReservation> movable = all.stream()
                .filter(reservation -> canMove(reservation.getStatus(), target))
                .toList();
        if (movable.isEmpty()) {
            // Повторное освобождение ничего не перемещает.
            return ReservationTransition.ALREADY_APPLIED;
        }

        Map<UUID, Product> locked = products.lockAllByIdIn(
                        movable.stream().map(StockReservation::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (StockReservation reservation : movable) {
            Product product = locked.get(reservation.getProductId());
            if (product == null) {
                throw new IllegalStateException(
                        "Reservation " + reservation.getId() + " points at a missing product");
            }
            if (target == ReservationStatus.RELEASED) {
                // Источник остатков определяется до смены статуса. Для COMMITTED возвращается продажа, для RESERVED освобождается резерв.
                if (reservation.getStatus() == ReservationStatus.COMMITTED) {
                    product.returnCommitted(reservation.getQuantity());
                }
                else {
                    product.releaseReserved(reservation.getQuantity());
                }
                reservation.release();
            }
            else {
                product.commitReserved(reservation.getQuantity());
                reservation.commit();
            }
        }
        reservations.flush();
        products.flush();
        log.info("Moved {} reservation line(s) of order {} to {}", movable.size(), orderId, target);
        return ReservationTransition.APPLIED;
    }

    /**
     * Отметка об освобождении фиксируется вместе со строками. {@code ON CONFLICT} сохраняет первую причину при любом повторе.
     */
    private ReservationTransition releaseAndRecordIntent(UUID orderId, String reason) {
        ReservationTransition transition = transition(orderId, ReservationStatus.RELEASED);
        releaseIntents.insertIfAbsent(orderId, releaseReason(reason));
        return transition;
    }

    private static String releaseReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_RELEASE_REASON;
        }
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }

    /**
     * @param current текущий статус строки
     * @param target целевой статус
     * @return допустимость перехода; освобождённая строка не восстанавливается, подтверждение выполняется один раз
     */
    private static boolean canMove(ReservationStatus current, ReservationStatus target) {
        if (target == ReservationStatus.RELEASED) {
            return current == ReservationStatus.RESERVED || current == ReservationStatus.COMMITTED;
        }
        return current == ReservationStatus.RESERVED;
    }

    /**
     * @param items строки заказа
     * @return количество по товарам в стабильном порядке. Одинаковые товары объединяются для {@code UNIQUE(order_id, product_id)}
     */
    private static Map<UUID, Integer> quantitiesByProduct(List<OrderItem> items) {
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        for (OrderItem item : items) {
            wanted.merge(item.productId(), item.quantity(), Integer::sum);
        }
        return wanted;
    }
}
