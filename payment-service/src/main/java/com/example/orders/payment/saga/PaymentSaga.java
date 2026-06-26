package com.example.orders.payment.saga;

import com.example.orders.common.events.ProcessPayment;
import com.example.orders.common.events.RefundPayment;
import com.example.orders.support.tx.OptimisticRetry;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Точки входа платёжных шагов Saga с ограниченными повторами транзакций.
 */
@Service
public class PaymentSaga {

    private final PaymentSagaTransactions transactions;
    private final int maxAttempts;

    PaymentSaga(
            PaymentSagaTransactions transactions,
            @Value("${payments.max-attempts:3}") int maxAttempts) {
        this.transactions = transactions;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param eventId идентификатор для дедупликации по {@code processed_events}
     * @param command команда оплаты
     */
    public void processPayment(UUID eventId, ProcessPayment command) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onProcessPayment(eventId, command);
            return null;
        });
    }

    /**
     * @param eventId идентификатор события
     * @param command команда возврата
     */
    public void refundPayment(UUID eventId, RefundPayment command) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onRefundPayment(eventId, command);
            return null;
        });
    }
}
