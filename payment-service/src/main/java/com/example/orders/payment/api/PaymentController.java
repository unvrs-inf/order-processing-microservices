package com.example.orders.payment.api;

import com.example.orders.payment.service.PaymentQueryService;
import com.example.orders.support.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API чтения платежа для владельца заказа или администратора.
 */
@RestController
@RequestMapping("/api/payments")
class PaymentController {

    /** Роль для просмотра платежей других пользователей. */
    private static final String ADMIN = "ADMIN";

    private final PaymentQueryService payments;

    PaymentController(PaymentQueryService payments) {
        this.payments = payments;
    }

    /**
     * @param orderId идентификатор заказа
     * @return платёж по заказу
     */
    @GetMapping("/order/{orderId}")
    PaymentResponse byOrder(@PathVariable UUID orderId) {
        return payments.byOrder(orderId, CurrentUser.requireId(), CurrentUser.hasRole(ADMIN));
    }
}
