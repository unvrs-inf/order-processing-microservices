package com.example.orders.support.error;

/**
 * Некорректный запрос, который нельзя полностью проверить декларативной валидацией. API возвращает 400.
 */
public class BadRequestException extends RuntimeException {

    /**
     * @param message описание ошибки, которое можно показать клиенту
     */
    public BadRequestException(String message) {
        super(message);
    }
}
