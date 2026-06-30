package com.example.orders.payment.service;

import com.example.orders.payment.api.PaymentResponse;
import com.example.orders.payment.domain.PaymentRepository;
import com.example.orders.support.error.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Чтение платежей. Изменения выполняет обработчик команд Saga.
 */
@Service
public class PaymentQueryService {

    private final PaymentRepository payments;

    PaymentQueryService(PaymentRepository payments) {
        this.payments = payments;
    }

    /**
     * Чужой платёж возвращает {@code 404}, чтобы не раскрывать его существование. Администратор видит все платежи.
     * @param orderId идентификатор заказа
     * @param callerId идентификатор текущего пользователя
     * @param admin наличие роли администратора
     * @throws NotFoundException если запись отсутствует или недоступна пользователю
     */
    @Transactional(readOnly = true)
    public PaymentResponse byOrder(UUID orderId, UUID callerId, boolean admin) {
        return payments.findByOrderId(orderId)
                .filter(payment -> admin || payment.getCustomerId().equals(callerId))
                .map(PaymentResponse::of)
                .orElseThrow(() -> new NotFoundException("No payment exists for order " + orderId));
    }
}
