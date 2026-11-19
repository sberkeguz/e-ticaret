package com.sarikaya.e_ticaret;

import com.sarikaya.e_ticaret.payment.service.PaymentService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Uygulama gerçek PostgreSQL + RabbitMQ ile ayağa kalkar; şema güncel ve ana parçalar bağlı. */
@IntegrationTest
class ETicaretApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private Flyway flyway;

	@Test
	void contextLoads() {
		assertNotNull(context.getBean(SecurityFilterChain.class));
		assertNotNull(context.getBean(PaymentService.class));
		assertEquals(0, flyway.info().pending().length, "Uygulanmamış Flyway migration'ı kaldı");
	}

}
