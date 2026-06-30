package com.example.orders.inventory.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Чтение резервов по заказу для Saga и по товару при удалении. */
public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    List<StockReservation> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    List<StockReservation> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);

    boolean existsByProductIdAndStatus(UUID productId, ReservationStatus status);

    void deleteByProductId(UUID productId);
}
