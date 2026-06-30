package com.example.orders.support.error;

/**
 * Зависимость недоступна или не успела ответить. API возвращает 503.
 */
public class ServiceUnavailableException extends RuntimeException {

    /**
     * @param message описание зависимости для клиента без внутренних подробностей
     */
    public ServiceUnavailableException(String message) {
        super(message);
    }

    /**
     * @param message описание зависимости для клиента
     * @param cause исходная причина ошибки
     */
    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
