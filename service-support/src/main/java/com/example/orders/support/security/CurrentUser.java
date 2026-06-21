package com.example.orders.support.security;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Читает данные текущего пользователя из контекста безопасности.
 */
public final class CurrentUser {

    /** Префикс роли Spring Security. В поле {@code roles} роли идут без него. */
    public static final String ROLE_PREFIX = "ROLE_";

    private CurrentUser() {
    }

    /**
     * @return идентификатор пользователя или пустое значение для анонимного запроса либо неверного {@code sub}
     */
    public static Optional<UUID> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        String subject = authentication.getPrincipal() instanceof Jwt jwt
                ? jwt.getSubject()
                : authentication.getName();
        return parseUuid(subject);
    }

    /**
     * @return идентификатор пользователя
     * @throws IllegalStateException если состояние не позволяет выполнить операцию
     */
    public static UUID requireId() {
        return id().orElseThrow(() -> new IllegalStateException("No authenticated user in the security context"));
    }

    /**
     * @return список ролей без префикса {@code ROLE_}, например {@code ["ADMIN"]}
     */
    public static Set<String> roles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith(ROLE_PREFIX)) {
                roles.add(name.substring(ROLE_PREFIX.length()));
            }
        }
        return roles;
    }

    /**
     * @param role имя роли без префикса {@code ROLE_}
     */
    public static boolean hasRole(String role) {
        return roles().contains(role);
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        }
        catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
