package com.example.orders.payment.gateway;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Имитатор платёжного провайдера. Оплата записывает {@code AUTHORIZED} в базу сервиса, а возврат меняет состояние строки. Запросы к эквайеру не отправляются.
 * <p>Решение детерминировано для одного заказа, поэтому повторный {@code ProcessPayment} получает тот же ответ.
 * <ul>
 * <li>{@code PAYMENT_DECLINE_ABOVE} (1000.00 по умолчанию) задаёт верхний порог суммы.</li>
 * <li>{@code PAYMENT_FAILURE_RATE} (0 по умолчанию) задаёт долю отклонений. Решение вычисляется из хеша идентификатора заказа.</li>
 * </ul>
 */
@Component
public class SimulatedPaymentGateway {

    /** Причина отклонения суммы выше порога. */
    public static final String DECLINED_AMOUNT = "Payment declined: amount %s %s exceeds the limit of %s";

    /** Причина отклонения по заданной доле отказов. */
    public static final String DECLINED_RATE = "Payment declined by the provider (simulated failure rate)";

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final BigDecimal declineAbove;
    private final double failureRate;

    /**
     * @param declineAbove порог суммы, выше которого оплата отклоняется
     * @param failureRate доля отклонений независимо от суммы, от {@code 0} до {@code 1}
     */
    SimulatedPaymentGateway(
            @Value("${PAYMENT_DECLINE_ABOVE:1000.00}") BigDecimal declineAbove,
            @Value("${PAYMENT_FAILURE_RATE:0}") double failureRate) {
        this.declineAbove = declineAbove;
        this.failureRate = failureRate;
    }

    /**
     * @param orderId идентификатор заказа, из которого вычисляется стабильное решение
     * @param amount сумма платежа
     * @param currency код ISO 4217
     * @return решение имитатора
     */
    public GatewayDecision charge(UUID orderId, BigDecimal amount, String currency) {
        if (amount == null || amount.compareTo(declineAbove) > 0) {
            String reason = DECLINED_AMOUNT.formatted(amount, currency, declineAbove.toPlainString());
            log.info("Simulated gateway declined order {}: {}", orderId, reason);
            return GatewayDecision.declined(reason);
        }
        if (failsByRate(orderId)) {
            log.info("Simulated gateway declined order {} by failure rate {}", orderId, failureRate);
            return GatewayDecision.declined(DECLINED_RATE);
        }
        return GatewayDecision.approved(providerReference(orderId));
    }

    /**
     * @param orderId идентификатор заказа
     * @return попадает ли заказ в неизменную для него долю отказов
     */
    private boolean failsByRate(UUID orderId) {
        if (failureRate <= 0 || orderId == null) {
            return false;
        }
        if (failureRate >= 1) {
            return true;
        }
        int bucket = Math.floorMod(orderId.hashCode(), 100);
        return bucket < Math.round(failureRate * 100);
    }

    /**
     * @param orderId идентификатор заказа
     * @return стабильный идентификатор операции, вычисленный из идентификатора заказа
     */
    private static String providerReference(UUID orderId) {
        UUID derived = UUID.nameUUIDFromBytes(("payment:" + orderId).getBytes(StandardCharsets.UTF_8));
        return "SIM-" + derived.toString().substring(0, 13).replace("-", "").toUpperCase(Locale.ROOT);
    }
}
