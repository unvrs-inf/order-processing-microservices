package com.example.orders.support.error;

/**
 * Запрос конфликтует с текущим состоянием, например с занятым бизнес-ключом. API возвращает 409.
 */
public class ConflictException extends RuntimeException {

    /**
     * @param message описание конфликта для клиента
     */
    public ConflictException(String message) {
        super(message);
    }
}
