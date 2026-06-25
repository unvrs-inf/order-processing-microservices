package com.example.orders.payment.gateway;

/**
 * Ответ имитатора платёжного провайдера.
 * @param approved признак одобрения оплаты
 * @param providerRef идентификатор операции у платёжного провайдера
 * @param reason причина операции
 */
public record GatewayDecision(boolean approved, String providerRef, String reason) {

    /**
     * @param providerRef идентификатор операции у имитатора
     * @return успешное решение
     */
    public static GatewayDecision approved(String providerRef) {
        return new GatewayDecision(true, providerRef, null);
    }

    /**
     * @param reason причина отклонения
     * @return решение об отказе
     */
    public static GatewayDecision declined(String reason) {
        return new GatewayDecision(false, null, reason);
    }
}
