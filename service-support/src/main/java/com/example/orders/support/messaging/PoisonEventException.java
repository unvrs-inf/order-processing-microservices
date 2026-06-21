package com.example.orders.support.messaging;

/**
 * Запись Kafka не удаётся распознать как допустимое сообщение проекта.
 */
public class PoisonEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message описание ошибки в записи
     */
    public PoisonEventException(String message) {
        super(message);
    }

    /**
     * @param message описание ошибки в записи
     * @param cause исходная причина ошибки
     */
    public PoisonEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
