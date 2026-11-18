package com.sarikaya.e_ticaret;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Spring testleri için geçici PostgreSQL ve RabbitMQ. Bağlantı ayarları (url, kullanıcı, şifre)
 * otomatik bu konteynerlere yönlenir; geliştirme veritabanındaki gerçek siparişlere ve outbox'a,
 * geliştirme RabbitMQ'sundaki kuyruklara dokunulmaz.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainers {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitmq() {
        return new RabbitMQContainer("rabbitmq:4-alpine");
    }
}
