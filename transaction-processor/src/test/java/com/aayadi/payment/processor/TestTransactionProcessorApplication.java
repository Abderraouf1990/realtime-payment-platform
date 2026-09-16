package com.aayadi.payment.processor;

import org.springframework.boot.SpringApplication;

public class TestTransactionProcessorApplication {

	public static void main(String[] args) {
		SpringApplication.from(TransactionProcessorApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
