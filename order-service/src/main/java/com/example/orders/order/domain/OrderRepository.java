package com.example.orders.order.domain;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Поиск заказов по идентификатору и владельцу. Администратор может читать заказы всех владельцев. */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * @param id идентификатор заказа
     * @return заказ с загруженными строками для формирования ответа вне сессии, поскольку {@code open-in-view} выключен
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    /**
     * Поиск результата повторного запроса. Один {@code Idempotency-Key} могут независимо использовать разные пользователи.
     * @param userId владелец заказа
     * @param idempotencyKey ключ из запроса
     * @return ранее созданный заказ, если он есть
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    // Запросы страниц загружают строки пакетами в открытой транзакции чтения. JOIN FETCH для коллекции переносил бы пагинацию в память.

    /**
     * @param userId владелец
     * @param pageable параметры страницы и сортировки
     * @return заказы пользователя
     */
    Page<Order> findByUserId(UUID userId, Pageable pageable);

    /**
     * @param status фильтр по статусу
     * @param pageable параметры страницы и сортировки
     * @return заказы с этим статусом у всех владельцев
     */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * Заказы с устаревшим переходом независимо от возврата. Проверка тайм-аутов использует отдельный запрос без незавершённых возвратов.
     * @param statuses незавершённые состояния из {@link OrderStatus#UNFINISHED}
     * @param cutoff крайний срок последнего изменения
     * @return до 50 заказов, начиная с наиболее старых
     */
    List<Order> findTop50ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<OrderStatus> statuses, OffsetDateTime cutoff);

    /**
     * Выборка для проверки тайм-аутов Saga. Исключение возвратов на уровне запроса не даёт им занять всю пачку. Для них действует отдельный интервал по {@code refund_retry_at}.
     * @param statuses незавершённые состояния из {@link OrderStatus#UNFINISHED}
     * @param excludedRefund состояние возврата, например {@link RefundStatus#REQUESTED}
     * @param cutoff крайний срок последнего изменения
     * @return до 50 зависших заказов, начиная с наиболее старых
     */
    List<Order> findTop50ByStatusInAndRefundStatusNotAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<OrderStatus> statuses, RefundStatus excludedRefund, OffsetDateTime cutoff);

    /**
     * Выборка для повторных запросов возврата. Сначала берутся самые старые попытки по полю {@code refund_retry_at}.
     * @param refundStatus ожидающий возврат, {@link RefundStatus#REQUESTED}
     * @param statuses статусы заказов, у которых остался только возврат
     * @param dueBefore время, к которому попытка считается просроченной
     * @return до 50 возвратов, для которых пора повторить команду
     */
    List<Order> findTop50ByRefundStatusAndStatusInAndRefundRetryAtBeforeOrderByRefundRetryAtAsc(
            RefundStatus refundStatus, Collection<OrderStatus> statuses, OffsetDateTime dueBefore);
}
