package com.example.orders.auth.domain;

import java.util.Locale;

/** Общие правила сравнения адресов для всего сервиса. */
public final class Emails {

    private Emails() {
    }

    /**
     * Удаляет крайние пробелы и приводит почту к нижнему регистру.
     * @param raw значение из запроса, может быть {@code null}
     * @return нормализованный адрес или {@code null}
     */
    public static String normalize(String raw) {
        return raw == null ? null : raw.strip().toLowerCase(Locale.ROOT);
    }
}
