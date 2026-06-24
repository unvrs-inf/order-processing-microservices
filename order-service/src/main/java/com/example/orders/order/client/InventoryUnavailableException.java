package com.example.orders.order.client;

import com.example.orders.support.error.ServiceUnavailableException;

/**
 * Сейчас нельзя получить цены из каталога. API возвращает {@code 503}.
 */
public class InventoryUnavailableException extends ServiceUnavailableException {

    /**
     * @param message описание ошибки для клиента
     */
    public InventoryUnavailableException(String message) {
        super(message);
    }

    /**
     * @param message описание ошибки для клиента
     * @param cause исходная причина ошибки
     */
    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
