package com.example.orders.support;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Общее окружение тестов надёжности с PostgreSQL и Kafka.
 */
@SpringBootTest(classes = SupportTestApplication.class)
public abstract class SupportIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.2"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void supportProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.kafka.consumer.group-id", () -> "support-test");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        // Включаем все три компонента так же, как в прикладном сервисе.
        registry.add("outbox.enabled", () -> true);
        registry.add("outbox.aggregate-type", () -> "TestAggregate");
        registry.add("idempotency.enabled", () -> true);
        registry.add("messaging.consumer.enabled", () -> true);
        // Планировщики выключены. Тесты сами управляют публикацией и очисткой.
        registry.add("outbox.scheduler-enabled", () -> false);
        registry.add("idempotency.scheduler-enabled", () -> false);
    }

    /** @return уникальное имя топика для этого теста */
    protected static String uniqueTopic(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Опрашивает условие до выполнения или истечения срока.
     * @param timeout предельное время ожидания
     * @param condition условие окончания ожидания
     */
    protected static void awaitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting", e);
            }
        }
        throw new AssertionError("Condition did not become true within " + timeout);
    }
}
