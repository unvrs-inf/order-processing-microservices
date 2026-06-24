package com.example.orders.inventory.saga;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.ReleaseStock;
import com.example.orders.support.tx.OptimisticRetry;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Точки входа складских шагов с ограниченным числом повторов транзакции.
 */
@Service
public class InventorySaga {

    private final InventorySagaTransactions transactions;
    private final int maxAttempts;

    InventorySaga(
            InventorySagaTransactions transactions,
            @Value("${inventory.reservation.max-attempts:3}") int maxAttempts) {
        this.transactions = transactions;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param eventId идентификатор для {@code processed_events}
     * @param event тело события
     */
    public void orderCreated(UUID eventId, OrderCreated event) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onOrderCreated(eventId, event);
            return null;
        });
    }

    /**
     * @param eventId идентификатор события
     * @param command команда подтверждения
     */
    public void commitStock(UUID eventId, CommitStock command) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onCommitStock(eventId, command);
            return null;
        });
    }

    /**
     * @param eventId идентификатор события
     * @param command команда освобождения
     */
    public void releaseStock(UUID eventId, ReleaseStock command) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onReleaseStock(eventId, command);
            return null;
        });
    }
}
