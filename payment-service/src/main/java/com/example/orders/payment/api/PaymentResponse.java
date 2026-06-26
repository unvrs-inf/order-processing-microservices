package com.example.orders.payment.api;

import com.example.orders.payment.domain.Payment;
import com.example.orders.payment.domain.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Представление платежа для API. Версия строки хранится внутри сервиса.
 * @param id идентификатор записи
 * @param orderId идентификатор заказа
 * @param customerId идентификатор покупателя
 * @param amount сумма платежа
 * @param currency код валюты ISO 4217
 * @param status статус
 * @param failureReason причина отказа
 * @param providerRef идентификатор операции у платёжного провайдера
 * @param simulated признак симулированной операции
 * @param createdAt время создания
 * @param updatedAt время последнего изменения
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
        UUID id,
        UUID orderId,
        UUID customerId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String failureReason,
        String providerRef,
        boolean simulated,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param payment сохранённый платёж
     * @return представление платежа для API
     */
    public static PaymentResponse of(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getCustomerId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getFailureReason(),
                payment.getProviderRef(),
                true,
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
