package com.example.orders.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Сервис симулированных платежей и возвратов. Подключает общие сущности outbox и дедупликации. Таблицы создаёт Flyway в базе платежей.
 */
@SpringBootApplication
@EntityScan(basePackages = {"com.example.orders.payment", "com.example.orders.support"})
@EnableJpaRepositories(basePackages = {"com.example.orders.payment", "com.example.orders.support"})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
