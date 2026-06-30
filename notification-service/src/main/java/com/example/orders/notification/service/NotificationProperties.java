package com.example.orders.notification.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры демонстрационной почты. Получатель строится из идентификатора клиента и заданного домена.
 * @param from адрес отправителя
 * @param recipientDomain демонстрационный домен получателя
 * @param emailEnabled если {@code false}, уведомления сохраняются со статусом SKIPPED без отправки
 */
@ConfigurationProperties("notifications")
public record NotificationProperties(String from, String recipientDomain, boolean emailEnabled) {
}
