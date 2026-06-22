package com.example.orders.gateway.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Проверяет значение {@code X-Correlation-Id} и заменяет недопустимое.
 */
public final class CorrelationId {

    private static final Pattern SANE = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private CorrelationId() {
    }

    /**
     * @param candidate клиентское значение, может быть {@code null}
     * @return проверенное значение или новый UUID
     */
    public static String sanitise(String candidate) {
        return isSane(candidate) ? candidate : UUID.randomUUID().toString();
    }

    /**
     * @param candidate клиентское значение, может быть {@code null}
     * @return допустимость использования значения
     */
    public static boolean isSane(String candidate) {
        return candidate != null && SANE.matcher(candidate).matches();
    }
}
