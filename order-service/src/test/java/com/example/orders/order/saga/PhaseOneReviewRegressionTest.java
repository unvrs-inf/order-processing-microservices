package com.example.orders.order.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.orders.common.events.OrderCancelled;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.RefundPayment;
import com.example.orders.common.events.Topics;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.RefundStatus;
import com.example.orders.support.idempotency.ProcessedEvents;
import com.example.orders.support.outbox.OutboxWriter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Регрессии отслеживания возврата и повторного запроса по отдельному интервалу.
 */
class PhaseOneReviewRegressionTest {

    private static final String CURRENCY = "USD";
    private static final String OUTBOX_REFUND = RefundPayment.class.getSimpleName();
    private static final String OUTBOX_CANCELLED = OrderCancelled.class.getSimpleName();

    private OrderRepository repository;
    private OutboxWriter outbox;
    private ProcessedEvents processedEvents;
    private OrderSagaTransactions transactions;

    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        outbox = mock(OutboxWriter.class);
        processedEvents = mock(ProcessedEvents.class);
        when(processedEvents.markProcessed(any(), anyString())).thenReturn(true);
        when(repository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        transactions = new OrderSagaTransactions(repository, outbox, processedEvents);
    }

    @Test
    void foreignPaymentRefundedMustNotCompleteTrackedRefund() {
        UUID trackedPayment = UUID.randomUUID();
        UUID foreignPayment = UUID.randomUUID();
        Order order = pricedOrder();
        order.markConfirmed(trackedPayment);
        assertThat(order.requestRefund()).isTrue();
        order.markCancellationPending(OrderSagaTransactions.CANCELLED_BY_CUSTOMER);
        stubFind(order);

        transactions.onPaymentRefunded(UUID.randomUUID(),
                new PaymentRefunded(order.getId(), foreignPayment, order.getTotalAmount(), CURRENCY, "foreign"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
        assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(order.getPaymentId()).isEqualTo(trackedPayment);
        verify(outbox, never()).record(any(UUID.class), eq(OUTBOX_CANCELLED), anyString(), any());
    }

    @Test
    void cancelledOrderWithRequestedRefundMustBeEligibleForRetry() {
        UUID trackedPayment = UUID.randomUUID();
        Order order = pricedOrder();
        // Оплата завершилась после отмены. Конечный заказ всё ещё требует возврата.
        order.rememberPayment(trackedPayment);
        assertThat(order.requestRefund()).isTrue();
        order.markCancelled("Cancelled by the customer");
        stubFind(order);

        assertThat(transactions.recoverOutstandingRefund(
                order.getId(), "refund timeout", OffsetDateTime.now().plusSeconds(60))).isTrue();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).record(eq(order.getId()), eq(OUTBOX_REFUND), eq(Topics.PAYMENT_COMMANDS), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(RefundPayment.class);
        RefundPayment command = (RefundPayment) payload.getValue();
        assertThat(command.orderId()).isEqualTo(order.getId());
        assertThat(command.paymentId()).isEqualTo(trackedPayment);
        assertThat(command.amount()).isEqualByComparingTo(order.getTotalAmount());
        assertThat(command.currency()).isEqualTo(CURRENCY);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
        // Отмена уже объявлена. Повтор возврата не отправляет новое уведомление.
        verify(outbox, never()).record(any(UUID.class), eq(OUTBOX_CANCELLED), anyString(), any());
    }

    @Test
    void cancellationPendingRefundMustStayRetryableAfterFirstTimeout() {
        UUID trackedPayment = UUID.randomUUID();
        Order order = pricedOrder();
        order.markConfirmed(trackedPayment);
        assertThat(order.requestRefund()).isTrue();
        order.markCancellationPending(OrderSagaTransactions.CANCELLED_BY_CUSTOMER);
        stubFind(order);

        // Заказ ждёт возврата, а тайм-аут Saga не определяет его повтор.
        OffsetDateTime booked = order.getRefundRetryAt();
        assertThat(transactions.timeOut(order.getId(), "refund timeout 0")).isFalse();
        assertThat(transactions.timeOut(order.getId(), "refund timeout 0 again")).isFalse();
        verify(outbox, never()).record(any(UUID.class), eq(OUTBOX_REFUND), anyString(), any());
        assertThat(order.getRefundRetryAt()).isEqualTo(booked);

        // Обе попытки происходят в отдельных допустимых окнах.
        Duration retryInterval = Duration.ofMinutes(10);
        order.bookRefundRetry(OffsetDateTime.now().minus(retryInterval).minusSeconds(5));
        assertThat(transactions.recoverOutstandingRefund(
                order.getId(), "refund timeout 1", OffsetDateTime.now().minus(retryInterval))).isTrue();
        // Новая отметка закрывает окно для того же срока.
        assertThat(transactions.recoverOutstandingRefund(
                order.getId(), "refund immediate repeat", OffsetDateTime.now().minus(retryInterval))).isFalse();
        // Сдвигаем отметку вручную, чтобы открыть следующее окно.
        order.bookRefundRetry(OffsetDateTime.now().minus(retryInterval).minusSeconds(5));
        assertThat(transactions.recoverOutstandingRefund(
                order.getId(), "refund timeout 2", OffsetDateTime.now().minus(retryInterval))).isTrue();

        // Обе попытки записывают команду, сохраняя ожидающий статус заказа.
        verify(outbox, times(2)).record(
                eq(order.getId()), eq(OUTBOX_REFUND), eq(Topics.PAYMENT_COMMANDS), any(RefundPayment.class));
        // Уведомление об отмене не публикуется повторно во время возврата.
        verify(outbox, atMost(1)).record(any(UUID.class), eq(OUTBOX_CANCELLED), anyString(), any());
        assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(List.of(OrderStatus.CANCELLATION_PENDING, OrderStatus.CANCELLED))
                .contains(order.getStatus());
    }

    private void stubFind(Order order) {
        when(repository.findById(order.getId())).thenReturn(Optional.of(order));
    }

    private static Order pricedOrder() {
        Order order = Order.pending(UUID.randomUUID(), CURRENCY, null);
        order.addLine(UUID.randomUUID(), "Widget", new BigDecimal("12.50"), 2);
        return order;
    }
}
