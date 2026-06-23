package com.example.orders.order.saga;

import com.example.orders.common.events.PaymentCompleted;
import com.example.orders.common.events.PaymentFailed;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.StockRejected;
import com.example.orders.common.events.StockReserved;
import com.example.orders.order.api.OrderResponse;
import com.example.orders.support.tx.OptimisticRetry;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Точки входа Saga. Каждый метод ограниченное число раз повторяет транзакцию из {@link OrderSagaTransactions}. После конфликта версий новая транзакция читает состояние заново.
 * <p>После исчерпания попыток Kafka повторяет доставку и при следующем исчерпании отправляет запись в DLT. HTTP-отмена отвечает {@code 409}.
 */
@Service
public class OrderSaga {

    private final OrderSagaTransactions transactions;
    private final int maxAttempts;

    /**
     * @param transactions транзакции, которые повторяются целиком
     * @param maxAttempts предельное число попыток при конфликте версий
     */
    OrderSaga(
            OrderSagaTransactions transactions,
            @Value("${orders.saga.max-attempts:3}") int maxAttempts) {
        this.transactions = transactions;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param eventId идентификатор для дедупликации через {@code processed_events}
     * @param event тело события
     */
    public void stockReserved(UUID eventId, StockReserved event) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onStockReserved(eventId, event);
            return null;
        });
    }

    /**
     * @param eventId идентификатор события
     * @param event тело события
     */
    public void stockRejected(UUID eventId, StockRejected event) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onStockRejected(eventId, event);
            return null;
        });
    }

    /**
     * @param eventId идентификатор события
     * @param event тело события
     */
    public void paymentCompleted(UUID eventId, PaymentCompleted event) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onPaymentCompleted(eventId, event);
            return null;
        });
    }

    /**
     * @param eventId идентификатор события
     * @param event тело события
     */
    public void paymentFailed(UUID eventId, PaymentFailed event) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onPaymentFailed(eventId, event);
            return null;
        });
    }

    /**
     * @param eventId идентификатор события
     * @param event тело события
     */
    public void paymentRefunded(UUID eventId, PaymentRefunded event) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onPaymentRefunded(eventId, event);
            return null;
        });
    }

    /**
     * @param orderId заказ для отмены
     * @param callerId идентификатор пользователя
     * @param admin наличие роли {@code ADMIN}
     * @return состояние заказа после запроса отмены
     */
    public OrderResponse cancel(UUID orderId, UUID callerId, boolean admin) {
        return OptimisticRetry.execute(maxAttempts, () -> transactions.cancelOnRequest(orderId, callerId, admin));
    }

    /**
     * @param orderId заказ с истёкшим тайм-аутом
     * @param reason причина для клиента
     * @return признак изменения состояния
     */
    public boolean timeOut(UUID orderId, String reason) {
        return OptimisticRetry.execute(maxAttempts, () -> transactions.timeOut(orderId, reason));
    }

    /**
     * @param orderId заказ с ожидающим возвратом
     * @param reason причина в повторной команде
     * @param dueBefore предельное время последней попытки
     * @return признак повторной отправки команды
     */
    public boolean recoverOutstandingRefund(UUID orderId, String reason, OffsetDateTime dueBefore) {
        return OptimisticRetry.execute(maxAttempts,
                () -> transactions.recoverOutstandingRefund(orderId, reason, dueBefore));
    }
}
