package com.sarikaya.e_ticaret.payment;

import com.sarikaya.e_ticaret.payment.alerts.PaymentAlertRepository;
import com.sarikaya.e_ticaret.payment.events.DeadLetters;
import com.sarikaya.e_ticaret.payment.events.PaymentBroker;
import com.sarikaya.e_ticaret.payment.outbox.OutboxMessageRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
class PaymentHealthMetrics {
    PaymentHealthMetrics(MeterRegistry registry, DeadLetters deadLetters, PaymentAlertRepository alerts,
                         PaymentBroker broker, OutboxMessageRepository outbox) {
        // RabbitMQ kapalıyken sorulmaz: bağlantı denemesi asılı kalır ve tüm ölçümlerin gönderimini geciktirirdi.
        Gauge.builder("payment.events.dead_letters", () -> broker.isUp() ? safely(deadLetters::count) : Double.NaN)
                .description("DLQ'da bekleyen ödeme olayları")
                .register(registry);
        Gauge.builder("payment.alerts.open", () -> safely(alerts::countByResolvedAtIsNull))
                .description("Kapatılmamış ödeme uyarıları")
                .register(registry);
        Gauge.builder("payment.broker.up", () -> broker.isUp() ? 1 : 0)
                .description("RabbitMQ'ya ulaşılabiliyor mu (1/0). 0 iken 2D ödeme alınamaz.")
                .register(registry);
        Gauge.builder("payment.outbox.pending.age.seconds", () -> safely(() -> {
                    Instant oldest = outbox.oldestUnpublishedAt();
                    return oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toSeconds());
                }))
                .description("Outbox'ta RabbitMQ'ya gönderilmeyi bekleyen en eski mesajın yaşı (sn)")
                .register(registry);
    }

    private static double safely(java.util.function.LongSupplier read) {
        try {
            return read.getAsLong();
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }
}