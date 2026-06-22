package com.example.orders.auth.error;

/**
 * Для неизвестного адреса и неверного пароля возвращается одна ошибка входа.
 */
public class InvalidCredentialsException extends RuntimeException {

    /** Общее сообщение при неудачном входе. */
    public static final String MESSAGE = "Invalid email or password";

    public InvalidCredentialsException() {
        super(MESSAGE);
    }
}
