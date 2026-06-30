package com.example.orders.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Одна попытка платежа по заказу в таблице {@code payments}.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false, unique = true)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Payment() {
        // Конструктор для JPA.
    }

    private Payment(UUID orderId, UUID customerId, BigDecimal amount, String currency,
            PaymentStatus status, String failureReason, String providerRef) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        this.currency = currency;
        this.status = status;
        this.failureReason = trim(failureReason);
        this.providerRef = providerRef;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    /**
     * Фиксирует успешную симулированную оплату.
     * @param orderId оплаченный заказ
     * @param customerId идентификатор покупателя
     * @param amount сумма
     * @param currency код валюты ISO 4217
     * @param providerRef идентификатор от имитатора
     * @return новая авторизация
     */
    public static Payment authorized(
            UUID orderId, UUID customerId, BigDecimal amount, String currency, String providerRef) {
        return new Payment(orderId, customerId, amount, currency, PaymentStatus.AUTHORIZED, null, providerRef);
    }

    /**
     * Фиксирует отклонённую оплату.
     * @param orderId заказ с отказом оплаты
     * @param customerId идентификатор покупателя
     * @param amount сумма
     * @param currency код валюты ISO 4217
     * @param reason причина отказа
     * @return новая запись об отказе
     */
    public static Payment failed(
            UUID orderId, UUID customerId, BigDecimal amount, String currency, String reason) {
        return new Payment(orderId, customerId, amount, currency, PaymentStatus.FAILED, reason, null);
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Возвращает авторизованный платёж.
     * @param reason причина операции
     */
    public boolean refund(String reason) {
        if (this.status != PaymentStatus.AUTHORIZED) {
            return false;
        }
        this.status = PaymentStatus.REFUNDED;
        this.failureReason = trim(reason);
        return true;
    }

    /** @return находится ли платёж в состоянии авторизованного списания */
    public boolean isAuthorized() {
        return status == PaymentStatus.AUTHORIZED;
    }

    private static String trim(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public long getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
