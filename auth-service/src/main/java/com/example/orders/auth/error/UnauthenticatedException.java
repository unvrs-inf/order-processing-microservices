package com.example.orders.auth.error;

/** Токен прошёл проверку, но связанная учётная запись уже отсутствует. */
public class UnauthenticatedException extends RuntimeException {

    /** Используем ту же формулировку, что и для обычного отказа аутентификации. */
    public static final String MESSAGE = "Authentication is required to access this resource";

    public UnauthenticatedException() {
        super(MESSAGE);
    }
}
