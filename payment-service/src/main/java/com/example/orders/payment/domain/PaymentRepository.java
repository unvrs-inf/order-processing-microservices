package com.example.orders.payment.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Платежи адресуются по идентификатору заказа. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * @param orderId заказ, которому принадлежит платёж
     * @return платёж, если он найден
     */
    Optional<Payment> findByOrderId(UUID orderId);
}
