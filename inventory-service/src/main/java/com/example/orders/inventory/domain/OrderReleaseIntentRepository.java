package com.example.orders.inventory.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Чтение записей об освобождении. Запись через {@code ON CONFLICT DO NOTHING} сохраняет первую причину.
 */
public interface OrderReleaseIntentRepository extends JpaRepository<OrderReleaseIntent, UUID> {

    /**
     * @param orderId заказ, для которого нужно записать запрос на освобождение
     * @param reason причина операции
     */
    @Modifying
    @Query(value = """
            INSERT INTO order_release_intents (order_id, reason)
            VALUES (:orderId, :reason)
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") UUID orderId, @Param("reason") String reason);
}
