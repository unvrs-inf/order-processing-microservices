package com.example.orders.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Товар каталога со складскими остатками. Сущность отображается на таблицу {@code products}.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sku", nullable = false, length = 64, unique = true, updatable = false)
    private String sku;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    /** Версия для оптимистической блокировки конкурентных обновлений остатков. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Product() {
        // Конструктор для JPA.
    }

    private Product(String sku, String name, String description, BigDecimal price, int availableQuantity) {
        this.id = UUID.randomUUID();
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.price = price;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = 0;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    /**
     * @param sku уникальный артикул
     * @param name название
     * @param description описание
     * @param price цена
     * @param availableQuantity начальный остаток
     */
    public static Product create(
            String sku, String name, String description, BigDecimal price, int availableQuantity) {
        return new Product(sku, name, description, price, availableQuantity);
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Обновляет редактируемые поля каталога. SKU сохраняется как стабильный артикул.
     * @param name новое название
     * @param description новое описание
     * @param price новая цена
     */
    public void updateDetails(String name, String description, BigDecimal price) {
        this.name = name;
        this.description = description;
        this.price = price;
    }

    /**
     * @param delta изменение доступного остатка; отрицательное значение уменьшает количество
     */
    public boolean adjustAvailable(int delta) {
        long result = (long) this.availableQuantity + delta;
        if (result < 0 || result > Integer.MAX_VALUE) {
            return false;
        }
        this.availableQuantity = (int) result;
        return true;
    }

    /**
     * Перемещает единицы из доступного остатка в резерв.
     * @param quantity число единиц
     */
    public boolean reserve(int quantity) {
        if (quantity <= 0 || quantity > this.availableQuantity) {
            return false;
        }
        this.availableQuantity -= quantity;
        this.reservedQuantity += quantity;
        return true;
    }

    /**
     * Возвращает зарезервированные единицы в доступный остаток.
     * @param quantity число единиц
     */
    public void releaseReserved(int quantity) {
        this.reservedQuantity = Math.max(0, this.reservedQuantity - quantity);
        this.availableQuantity += quantity;
    }

    /**
     * Подтверждает продажу, списывая единицы из резерва.
     * @param quantity число единиц
     */
    public void commitReserved(int quantity) {
        this.reservedQuantity = Math.max(0, this.reservedQuantity - quantity);
    }

    /**
     * Возвращает проданный товар в доступный остаток.
     * @param quantity число единиц
     */
    public void returnCommitted(int quantity) {
        if (quantity <= 0) {
            return;
        }
        this.availableQuantity += quantity;
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
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
