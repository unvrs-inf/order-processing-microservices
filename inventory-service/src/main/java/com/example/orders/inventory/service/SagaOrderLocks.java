package com.example.orders.inventory.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Транзакционная advisory-блокировка для складских операций по одному заказу.
 * <p>{@code OrderCreated}, {@code ReleaseStock} и {@code CommitStock} приходят из разных топиков. Если освобождение обработано первым, резервирование увидит отметку. Если резервирование прошло первым, освобождение вернёт остатки. При одновременном вызове вторая транзакция ждёт первой.
 * <p>64-битный ключ вычисляется через {@code hashtextextended(orderId-text, 0)}. Совпадение ключей разных заказов вызывает ожидание, но данные не смешиваются. {@code pg_advisory_xact_lock} освобождается при завершении транзакции.
 * <p>Блокировка заказа берётся до блокировки товаров. {@code Statement.execute} подходит, поскольку функция возвращает {@code void}.
 */
@Component
public class SagaOrderLocks {

    private final JdbcTemplate jdbc;

    public SagaOrderLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Сериализует складские операции для {@code orderId} в транзакции вызывающего кода. {@link StockReservationTransactions} берёт её до первого чтения.
     * @param orderId идентификатор заказа для блокировки
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOrder(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        jdbc.execute((java.sql.Connection connection) -> {
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, orderId.toString());
                // Функция возвращает void. Результат вызова не нужен.
                statement.execute();
            }
            return true;
        });
    }
}
