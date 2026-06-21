package com.example.orders.support.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Проверяет и при необходимости заменяет {@code X-Correlation-Id} по тем же правилам, что и шлюз.
 */
public final class CorrelationId {

    /** HTTP-заголовок идентификатора корреляции. */
    public static final String HEADER = "X-Correlation-Id";

    /** Ключ SLF4J MDC для шаблона логов {@code %X{correlationId}}. */
    public static final String MDC_KEY = "correlationId";

    private static final Pattern SANE = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private CorrelationId() {
    }

    /**
     * @param candidate входное значение, может быть {@code null}
     * @return проверенное значение или новый UUID
     */
    public static String sanitise(String candidate) {
        return isSane(candidate) ? candidate : UUID.randomUUID().toString();
    }

    /**
     * @param candidate входное значение, может быть {@code null}
     * @return признак допустимого значения
     */
    public static boolean isSane(String candidate) {
        return candidate != null && SANE.matcher(candidate).matches();
    }
}
