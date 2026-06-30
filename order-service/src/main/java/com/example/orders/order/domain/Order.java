package com.example.orders.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Заказ со строками и зафиксированными ценами. Таблицу {@code orders} создаёт Flyway; {@code ddl-auto=validate} проверяет соответствие схемы.
 * <p>Имя сущности {@code OrderAggregate} позволяет избежать конфликта с ключевым словом {@code order} в запросах.
 * <p>Сумма заказа вычисляется из сохранённых строк при создании. Изменения каталога её не затрагивают, см. {@link OrderLine}.
 */
@Entity(name = "OrderAggregate")
@Table(name = "orders")
public class Order {

    /** Валюта заказов. Цены в нескольких валютах пока не поддерживаются. */
    public static final String DEFAULT_CURRENCY = "EUR";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Владелец заказа из поля {@code sub} токена. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * Идентификатор резерва из {@code StockReserved}. Резерв хранится в базе inventory-service, поэтому внешнего ключа здесь нет.
     */
    @Column(name = "reservation_id")
    private UUID reservationId;

    /** Идентификатор платежа из {@code PaymentCompleted}. */
    @Column(name = "payment_id")
    private UUID paymentId;

    /**
     * Состояние возврата для {@link #paymentId}. Учитывается отдельно от статуса заказа, см. {@link RefundStatus}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 20)
    private RefundStatus refundStatus = RefundStatus.NONE;

    /**
     * Время последней команды возврата для {@link #paymentId}. От него отсчитывается интервал повторной попытки, см. {@code V6__refund_recovery.sql}.
     */
    @Column(name = "refund_retry_at")
    private OffsetDateTime refundRetryAt;

    /** Версия для оптимистической блокировки конкурентных переходов Saga. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Строки принадлежат агрегату заказа. Каскад сохраняет их, удаление из коллекции удаляет строку.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("productName ASC")
    private List<OrderLine> items = new ArrayList<>();

    protected Order() {
        // Конструктор для JPA.
    }

    private Order(UUID userId, String currency, String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.status = OrderStatus.PENDING;
        this.totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    /**
     * @param userId владелец из токена
     * @param currency трёхбуквенный код ISO 4217
     * @param idempotencyKey ключ повторного запроса или {@code null}
     * @return пустой заказ {@code PENDING}; строки добавляются через {@link #addLine}
     */
    public static Order pending(UUID userId, String currency, String idempotencyKey) {
        return new Order(userId, currency, idempotencyKey);
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Добавляет строку и включает её сумму в итог заказа.
     * @param productId идентификатор в каталоге
     * @param productName название товара на момент заказа
     * @param unitPrice цена товара на момент заказа
     * @param quantity количество единиц, больше нуля
     * @return сохранённая строка
     */
    public OrderLine addLine(UUID productId, String productName, BigDecimal unitPrice, int quantity) {
        OrderLine line = new OrderLine(this, productId, productName, unitPrice, quantity);
        this.items.add(line);
        this.totalAmount = this.totalAmount.add(line.getLineTotal());
        return line;
    }

    // ------------------------------------------------------------------ переходы Saga
    // Допустимость перехода проверяет оркестратор, у которого есть событие и текущий контекст.

    /**
     * Резерв создан, команда оплаты отправлена.
     * @param reservationId идентификатор резерва или {@code null}, если он неизвестен
     */
    public void markPaymentPending(UUID reservationId) {
        this.status = OrderStatus.PAYMENT_PENDING;
        this.reservationId = reservationId;
        this.failureReason = null;
    }

    /**
     * Оплата прошла успешно.
     * @param paymentId идентификатор платежа
     */
    public void markConfirmed(UUID paymentId) {
        this.status = OrderStatus.CONFIRMED;
        this.paymentId = paymentId;
        this.failureReason = null;
    }

    /**
     * Заказ отклонён до резервирования или оплаты.
     * @param reason причина для клиента
     */
    public void markRejected(String reason) {
        this.status = OrderStatus.REJECTED;
        this.failureReason = trim(reason);
    }

    /**
     * Заказ отменён после начала обработки.
     * @param reason причина для клиента
     */
    public void markCancelled(String reason) {
        this.status = OrderStatus.CANCELLED;
        this.failureReason = trim(reason);
    }

    /**
     * Отмена принята, ожидается возврат.
     * @param reason причина для клиента
     */
    public void markCancellationPending(String reason) {
        this.status = OrderStatus.CANCELLATION_PENDING;
        this.failureReason = trim(reason);
    }

    /**
     * @param reservationId резерв, принадлежащий этому заказу
     */
    public void rememberReservation(UUID reservationId) {
        this.reservationId = reservationId;
    }

    /**
     * Записывает идентификатор позднего платежа для последующего возврата и сохраняет статус отменённого заказа.
     * @param paymentId идентификатор платежа
     */
    public void rememberPayment(UUID paymentId) {
        this.paymentId = paymentId;
    }

    /**
     * Помечает возврат как запрошенный. Вызывающая сторона записывает команду в той же транзакции.
     * @return {@code true}, если этот вызов запросил возврат; {@code false}, если возврат уже запрошен или завершён
     */
    public boolean requestRefund() {
        if (this.refundStatus != RefundStatus.NONE) {
            return false;
        }
        this.refundStatus = RefundStatus.REQUESTED;
        this.refundRetryAt = OffsetDateTime.now(ZoneOffset.UTC);
        return true;
    }

    /** Завершает отслеживаемый возврат по событию {@code PaymentRefunded}. */
    public void markRefunded() {
        this.refundStatus = RefundStatus.REFUNDED;
    }

    /**
     * Отмечает время повторного запроса. Первую попытку отмечает {@code requestRefund()}. Метка и запись outbox фиксируются вместе.
     * @param now время попытки
     */
    public void bookRefundRetry(OffsetDateTime now) {
        this.refundRetryAt = now;
    }

    private static String trim(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    /**
     * @return номер для клиента, полученный из идентификатора, например {@code ORD-3F2A1B7C}. Хранить отдельно его не требуется
     */
    public String getOrderNumber() {
        return orderNumberOf(this.id);
    }

    /**
     * @param orderId идентификатор заказа
     * @return номер в том же формате, что {@link #getOrderNumber()}
     */
    public static String orderNumberOf(UUID orderId) {
        return "ORD-" + orderId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public RefundStatus getRefundStatus() {
        return refundStatus;
    }

    public OffsetDateTime getRefundRetryAt() {
        return refundRetryAt;
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

    public List<OrderLine> getItems() {
        return List.copyOf(items);
    }
}
