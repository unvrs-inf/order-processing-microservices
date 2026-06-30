package com.example.orders.auth.domain;

/** Роли передаются в поле {@code roles}. Самостоятельная регистрация даёт {@link #USER}. */
public enum Role {
    USER,
    ADMIN
}
