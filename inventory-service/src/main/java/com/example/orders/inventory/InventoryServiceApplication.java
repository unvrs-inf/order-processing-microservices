package com.example.orders.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Каталог, остатки и складская часть Saga.
 */
@SpringBootApplication
@EntityScan(basePackages = {"com.example.orders.inventory", "com.example.orders.support"})
@EnableJpaRepositories(basePackages = {"com.example.orders.inventory", "com.example.orders.support"})
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
