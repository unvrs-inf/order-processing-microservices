package com.example.orders.auth.api;

import com.example.orders.auth.domain.Role;
import com.example.orders.auth.domain.UserAccount;
import java.util.UUID;

/**
 * Данные учётной записи, доступные клиенту. Хеш пароля не включается.
 * @param id идентификатор записи
 * @param name название
 * @param email адрес почты
 * @param role роль пользователя
 */
public record UserResponse(UUID id, String name, String email, Role role) {

    /**
     * @param user сохранённая учётная запись
     * @return публичное представление записи
     */
    public static UserResponse from(UserAccount user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
