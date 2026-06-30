package com.example.orders.auth.error;

/** Регистрация с уже занятым адресом. Сравнение без учёта регистра. */
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("Email is already registered: " + email);
    }
}
