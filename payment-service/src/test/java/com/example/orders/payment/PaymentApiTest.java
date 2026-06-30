package com.example.orders.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.payment.domain.Payment;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Проверяет видимость платежа по {@code sub} и доступ администратора.
 */
class PaymentApiTest extends PaymentIntegrationTest {

    @Test
    void theCustomerSeesTheirOwnPayment() throws Exception {
        UUID customerId = UUID.randomUUID();
        Payment payment = givenAuthorizedPayment(customerId, "99.90");

        mvc.perform(get("/api/payments/order/{orderId}", payment.getOrderId())
                        .header("Authorization", "Bearer " + token(customerId, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.getId().toString()))
                .andExpect(jsonPath("$.orderId").value(payment.getOrderId().toString()))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.amount").value(99.90))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.providerRef").isNotEmpty())
                // Ответ API явно сообщает, что платёж симулируется.
                .andExpect(jsonPath("$.simulated").value(true));
    }

    @Test
    void anAdministratorSeesSomebodyElsesPayment() throws Exception {
        Payment payment = givenAuthorizedPayment(UUID.randomUUID(), "10.00");

        mvc.perform(get("/api/payments/order/{orderId}", payment.getOrderId())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.getId().toString()));
    }

    @Test
    void anotherCustomerIsToldTheRecordDoesNotExist() throws Exception {
        Payment payment = givenAuthorizedPayment(UUID.randomUUID(), "10.00");

        mvc.perform(get("/api/payments/order/{orderId}", payment.getOrderId())
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void anOrderWithoutAPaymentIsA404() throws Exception {
        mvc.perform(get("/api/payments/order/{orderId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("No payment exists")));
    }

    @Test
    void aRequestWithoutATokenIsRejected() throws Exception {
        mvc.perform(get("/api/payments/order/{orderId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void anUnparsableOrderIdIsABadRequest() throws Exception {
        mvc.perform(get("/api/payments/order/not-a-uuid")
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isBadRequest());
    }

    /**
     * @param customerId идентификатор покупателя
     * @param amount сумма платежа
     * @return идентификатор заказа с платежом
     */
    private Payment givenAuthorizedPayment(UUID customerId, String amount) {
        return payments.saveAndFlush(Payment.authorized(
                UUID.randomUUID(), customerId, new BigDecimal(amount), "EUR", "SIM-TESTREF"));
    }
}
