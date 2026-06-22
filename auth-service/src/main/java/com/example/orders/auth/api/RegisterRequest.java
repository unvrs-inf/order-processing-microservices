package com.example.orders.auth.api;

import com.example.orders.auth.domain.Emails;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Данные регистрации. Почта нормализуется в конструкторе до валидации.
 * @param name отображаемое имя
 * @param email нормализованный адрес почты
 * @param password пароль длиной от 8 до 72 символов
 */
public record RegisterRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String name,

        @NotBlank(message = "must not be blank")
        @Email(message = "must be a well-formed email address")
        @Size(max = 255, message = "must be at most 255 characters")
        String email,

        @NotBlank(message = "must not be blank")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String password) {

    public RegisterRequest {
        name = name == null ? null : name.strip();
        email = Emails.normalize(email);
    }
}
