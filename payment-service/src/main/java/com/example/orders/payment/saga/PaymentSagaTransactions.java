package com.example.orders.payment.saga;

import com.example.orders.common.events.PaymentCompleted;
import com.example.orders.common.events.PaymentFailed;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.ProcessPayment;
import com.example.orders.common.events.RefundPayment;
import com.example.orders.common.events.Topics;
import com.example.orders.payment.domain.Payment;
import com.example.orders.payment.domain.PaymentRepository;
import com.example.orders.payment.gateway.GatewayDecision;
import com.example.orders.payment.gateway.SimulatedPaymentGateway;
import com.example.orders.support.idempotency.ProcessedEvents;
import com.example.orders.support.outbox.OutboxWriter;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Оплата и возвраты в Saga. Каждая команда обрабатывается отдельной транзакцией.
 * <ol>
 * <li>{@code processed_events} отсеивает повтор той же команды до вызова имитатора.</li>
 * <li>Другая команда для заказа с платежом повторно публикует сохранённый результат.</li>
 * <li>{@code UNIQUE(order_id)} защищает от конкурентного создания двух платежей.</li>
 * </ol>
 * <p>Запись платежа и исходящее событие фиксируются вместе.
 */
@Component
public class PaymentSagaTransactions {

    private static final Logger log = LoggerFactory.getLogger(PaymentSagaTransactions.class);

    /** Имя потребителя команд в {@code processed_events}. */
    public static final String COMMAND_CONSUMER = "payment.commands";

    private final PaymentRepository payments;
    private final SimulatedPaymentGateway gateway;
    private final OutboxWriter outbox;
    private final ProcessedEvents processedEvents;

    PaymentSagaTransactions(
            PaymentRepository payments,
            SimulatedPaymentGateway gateway,
            OutboxWriter outbox,
            ProcessedEvents processedEvents) {
        this.payments = payments;
        this.gateway = gateway;
        this.outbox = outbox;
        this.processedEvents = processedEvents;
    }

    /**
     * Выполняет симулированную оплату.
     * @param eventId идентификатор {@code ProcessPayment}
     * @param command тело команды
     */
    @Transactional
    public void onProcessPayment(UUID eventId, ProcessPayment command) {
        if (!processedEvents.markProcessed(eventId, COMMAND_CONSUMER)) {
            return;
        }
        if (command.orderId() == null) {
            log.warn("Ignoring ProcessPayment without an order id");
            return;
        }
        Optional<Payment> existing = payments.findByOrderId(command.orderId());
        if (existing.isPresent()) {
            Payment payment = existing.get();
            // Повторяем результат первой попытки. Оркестратор принимает дубликаты идемпотентно.
            log.info("Order {} already has payment {} in state {}, re-announcing that outcome",
                    command.orderId(), payment.getId(), payment.getStatus());
            announce(payment);
            return;
        }

        GatewayDecision decision = gateway.charge(command.orderId(), command.amount(), command.currency());
        Payment payment = decision.approved()
                ? Payment.authorized(command.orderId(), command.customerId(), command.amount(),
                        command.currency(), decision.providerRef())
                : Payment.failed(command.orderId(), command.customerId(), command.amount(),
                        command.currency(), decision.reason());
        // Проверяем UNIQUE(order_id) до конца текущей попытки.
        payments.saveAndFlush(payment);
        announce(payment);
        log.info("Payment {} for order {} is {} ({} {})",
                payment.getId(), payment.getOrderId(), payment.getStatus(), payment.getAmount(),
                payment.getCurrency());
    }

    /**
     * Возвращает авторизованный платёж.
     * @param eventId идентификатор {@code RefundPayment}
     * @param command тело команды
     */
    @Transactional
    public void onRefundPayment(UUID eventId, RefundPayment command) {
        if (!processedEvents.markProcessed(eventId, COMMAND_CONSUMER)) {
            return;
        }
        if (command.orderId() == null) {
            log.warn("Ignoring RefundPayment without an order id");
            return;
        }
        Optional<Payment> found = payments.findByOrderId(command.orderId());
        if (found.isEmpty()) {
            // Списания не было. Оркестратор мог компенсировать заказ до завершения оплаты.
            log.info("RefundPayment for order {} has nothing to refund", command.orderId());
            return;
        }
        Payment payment = found.get();
        String reason = command.reason() == null ? "Refunded after cancellation" : command.reason();
        if (payment.refund(reason)) {
            payments.saveAndFlush(payment);
            log.info("Refunded payment {} of order {}: {}", payment.getId(), payment.getOrderId(), reason);
            outbox.record(payment.getOrderId(), PaymentRefunded.class.getSimpleName(), Topics.PAYMENT_EVENTS,
                    new PaymentRefunded(payment.getOrderId(), payment.getId(), payment.getAmount(),
                            payment.getCurrency(), reason));
            return;
        }
        switch (payment.getStatus()) {
            case REFUNDED -> {
                // Возврат уже выполнен. Повторим уведомление на случай, если оркестратор ещё ждёт ответ.
                log.info("Payment {} of order {} was already refunded, re-announcing it",
                        payment.getId(), payment.getOrderId());
                outbox.record(payment.getOrderId(), PaymentRefunded.class.getSimpleName(),
                        Topics.PAYMENT_EVENTS,
                        new PaymentRefunded(payment.getOrderId(), payment.getId(), payment.getAmount(),
                                payment.getCurrency(), payment.getFailureReason()));
            }
            // Отклонённое списание не порождает возврат.
            default -> log.info("Payment {} of order {} is {}, nothing to refund",
                    payment.getId(), payment.getOrderId(), payment.getStatus());
        }
    }

    private void announce(Payment payment) {
        switch (payment.getStatus()) {
            case AUTHORIZED -> outbox.record(payment.getOrderId(), PaymentCompleted.class.getSimpleName(),
                    Topics.PAYMENT_EVENTS,
                    new PaymentCompleted(payment.getOrderId(), payment.getId(), payment.getAmount(),
                            payment.getCurrency()));
            case FAILED -> outbox.record(payment.getOrderId(), PaymentFailed.class.getSimpleName(),
                    Topics.PAYMENT_EVENTS,
                    new PaymentFailed(payment.getOrderId(), payment.getId(), payment.getAmount(),
                            payment.getCurrency(), payment.getFailureReason()));
            // Платёж уже возвращён. PaymentRefunded был опубликован при возврате.
            case REFUNDED -> log.info("Payment {} of order {} is already refunded, not re-announcing a charge",
                    payment.getId(), payment.getOrderId());
        }
    }
}
