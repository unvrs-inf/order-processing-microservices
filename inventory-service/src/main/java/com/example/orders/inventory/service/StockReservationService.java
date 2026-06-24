package com.example.orders.inventory.service;

import com.example.orders.common.events.OrderItem;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.support.error.BadRequestException;
import com.example.orders.support.tx.OptimisticRetry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Резервирование, освобождение и подтверждение складских остатков.
 * <p>Резерв создаётся для всех строк или отклоняется целиком. Повторная команда даёт тот же результат. Уникальное ограничение {@code UNIQUE(order_id, product_id)} защищает от двойной записи. Строки товаров блокируются, а конфликт версии приводит к ограниченному числу повторов.
 */
@Service
public class StockReservationService {

    private static final Logger log = LoggerFactory.getLogger(StockReservationService.class);

    /** Ограничение, по которому распознаётся уже созданный резерв. */
    private static final String ORDER_PRODUCT_UNIQUE = "uq_stock_reservations_order_product";

    private final StockReservationTransactions transactions;
    private final int maxAttempts;

    /**
     * @param transactions атомарные операции, которые можно повторить целиком
     * @param maxAttempts максимальное число попыток при конфликте версий
     */
    StockReservationService(
            StockReservationTransactions transactions,
            @Value("${inventory.reservation.max-attempts:3}") int maxAttempts) {
        this.transactions = transactions;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Резервирует все строки заказа или отклоняет запрос целиком.
     * @param orderId заказ
     * @param items строки заказа; количество одинакового товара суммируется
     * @return {@code RESERVED}, {@code ALREADY_RESERVED}, {@code REJECTED} с дефицитом по строкам или {@code ALREADY_RELEASED} при ранее записанном освобождении
     * @throws BadRequestException если строки отсутствуют, количество неположительное или нет идентификатора товара
     */
    public ReservationResult reserve(UUID orderId, List<OrderItem> items) {
        requireOrderId(orderId);
        validate(items);
        try {
            return OptimisticRetry.execute(maxAttempts, () -> transactions.reserve(orderId, items));
        }
        catch (DataIntegrityViolationException e) {
            if (!violatesOrderProductUnique(e)) {
                // Чужие нарушения ограничений нельзя трактовать как успешный резерв.
                throw e;
            }
            // Уникальное ограничение защитило от повторного резервирования между предварительной проверкой и записью.
            log.info("Concurrent duplicate reservation for order {} was rejected by the unique index", orderId);
            return ReservationResult.alreadyReserved(orderId);
        }
    }

    /**
     * Возвращает остатки в {@code available}. Для {@code RESERVED} единицы снимаются с резерва. Для {@code COMMITTED} возврат восстанавливает проданные единицы. Повторная команда пропускает освобождённые строки.
     * @param orderId заказ для освобождения
     * @return признак изменения остатков
     */
    public ReservationTransition release(UUID orderId) {
        requireOrderId(orderId);
        return OptimisticRetry.execute(maxAttempts, () -> transactions.release(orderId));
    }

    /**
     * Подтверждает продажу. Единицы списываются из {@code reserved} и не возвращаются в {@code available}.
     * @param orderId заказ для подтверждения
     * @return признак изменения остатков
     */
    public ReservationTransition commit(UUID orderId) {
        requireOrderId(orderId);
        return OptimisticRetry.execute(maxAttempts, () -> transactions.commit(orderId));
    }

    /**
     * @param orderId заказ для чтения
     * @return строки резерва в порядке создания; пустой список, если резерва не было
     */
    public List<StockReservation> reservationsOf(UUID orderId) {
        requireOrderId(orderId);
        return transactions.findByOrder(orderId);
    }

    private static boolean violatesOrderProductUnique(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(ORDER_PRODUCT_UNIQUE)) {
                return true;
            }
        }
        return false;
    }

    private static void requireOrderId(UUID orderId) {
        if (orderId == null) {
            throw new BadRequestException("orderId must be provided");
        }
    }

    private static void validate(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BadRequestException("An order must contain at least one item");
        }
        for (OrderItem item : items) {
            if (item == null || item.productId() == null) {
                throw new BadRequestException("Every order item must reference a product");
            }
            if (item.quantity() <= 0) {
                throw new BadRequestException("Quantity must be positive for product " + item.productId());
            }
        }
    }
}
