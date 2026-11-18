package com.sarikaya.e_ticaret;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Uygulamanın tamamıyla çalışan test. Veritabanı ve RabbitMQ geçici konteynerlerdir (TestContainers);
 * iyzico-service adresi bilerek ulaşılamaz bir adrestir: test, geliştirme ortamındaki servise istek atamaz.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "iyzico-service.url=http://127.0.0.1:1",
        // Yalnız test için; .env yüklenmeden de çalışsın ("test-only-jwt-secret-not-for-production!!").
        "jwt.secret=dGVzdC1vbmx5LWp3dC1zZWNyZXQtbm90LWZvci1wcm9kdWN0aW9uISE=",
})
@Import(TestContainers.class)
public @interface IntegrationTest {
}
