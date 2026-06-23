package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Проверяет запуск контекста с PostgreSQL и Kafka.
 */
class OrderServiceApplicationTests extends OrderIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(OrderServiceApplication.class)).isNotNull();
    }
}
