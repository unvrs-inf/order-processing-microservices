package com.example.orders.support.tx;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Повторяет транзакционную работу после конфликта оптимистической блокировки.
 */
public final class OptimisticRetry {

    private static final Logger log = LoggerFactory.getLogger(OptimisticRetry.class);

    private OptimisticRetry() {
    }

    /**
     * @param attempts максимальное число попыток, не меньше одной
     * @param work транзакционная операция для повтора
     * @param <T> тип элемента
     * @throws ObjectOptimisticLockingFailureException если конфликт версий сохраняется после всех попыток
     */
    public static <T> T execute(int attempts, Supplier<T> work) {
        int total = Math.max(1, attempts);
        ObjectOptimisticLockingFailureException last = null;
        for (int attempt = 1; attempt <= total; attempt++) {
            try {
                return work.get();
            }
            catch (ObjectOptimisticLockingFailureException e) {
                last = e;
                log.debug("Optimistic locking conflict on attempt {} of {}", attempt, total);
            }
        }
        throw last;
    }
}
