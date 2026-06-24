package com.example.orders.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Сервис заказов с оркестратором Saga.
 */
@SpringBootApplication
@EntityScan(basePackages = {"com.example.orders.order", "com.example.orders.support"})
@EnableJpaRepositories(basePackages = {"com.example.orders.order", "com.example.orders.support"})
@EnableScheduling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
