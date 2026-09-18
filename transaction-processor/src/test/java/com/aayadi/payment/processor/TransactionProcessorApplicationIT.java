package com.aayadi.payment.processor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransactionProcessorApplicationIT {

	@Test
	void contextLoads() {
	}

}
