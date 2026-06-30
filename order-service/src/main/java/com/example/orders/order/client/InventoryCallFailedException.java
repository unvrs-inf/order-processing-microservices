package com.example.orders.order.client;

/**
 * Ошибка запроса к inventory-service, которую можно повторить.
 */
public class InventoryCallFailedException extends InventoryUnavailableException {

    /**
     * @param message описание ошибки, допустимое для клиента
     */
    public InventoryCallFailedException(String message) {
        super(message);
    }

    /**
     * @param message описание ошибки, допустимое для клиента
     * @param cause исходная причина ошибки
     */
    public InventoryCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
