package com.example.orders.auth.api;

import com.example.orders.auth.domain.Emails;
import jakarta.validation.constraints.NotBlank;

/**
 * Данные входа. Адрес почты нормализуется так же, как при регистрации.
 * @param email адрес после удаления пробелов и приведения к нижнему регистру
 * @param password пароль
 */
public record LoginRequest(
        @NotBlank(message = "must not be blank") String email,
        @NotBlank(message = "must not be blank") String password) {

    public LoginRequest {
        email = Emails.normalize(email);
    }
}
