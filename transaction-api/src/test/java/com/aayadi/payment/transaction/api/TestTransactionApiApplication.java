package com.aayadi.payment.transaction.api;

import org.springframework.boot.SpringApplication;

public class TestTransactionApiApplication {

    public static void main(String[] args) {
        SpringApplication.from(TransactionApiApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
