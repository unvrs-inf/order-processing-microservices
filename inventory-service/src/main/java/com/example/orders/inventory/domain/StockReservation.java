package com.example.orders.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Резерв по одной товарной строке заказа.
 */
@Entity
@Table(name = "stock_reservations")
public class StockReservation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected StockReservation() {
        // Конструктор для JPA.
    }

    private StockReservation(UUID orderId, UUID productId, int quantity) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = ReservationStatus.RESERVED;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    /**
     * @param orderId заказ, которому принадлежит резерв
     * @param productId идентификатор товара
     * @param quantity количество единиц
     */
    public static StockReservation reserve(UUID orderId, UUID productId, int quantity) {
        return new StockReservation(orderId, productId, quantity);
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Отмечает резерв освобождённым. */
    public void release() {
        this.status = ReservationStatus.RELEASED;
    }

    /** Отмечает резерв подтверждённым. */
    public void commit() {
        this.status = ReservationStatus.COMMITTED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
