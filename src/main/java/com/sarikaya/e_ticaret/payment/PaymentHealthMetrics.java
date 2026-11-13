package com.sarikaya.e_ticaret.payment;

import com.sarikaya.e_ticaret.payment.alerts.PaymentAlertRepository;
import com.sarikaya.e_ticaret.payment.events.DeadLetters;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Grafana uyarıları için ödeme sağlığı ölçümleri (15 sn'de bir okunur):
 * - payment.events.dead_letters: işlenemeyip DLQ'da bekleyen ödeme olayı sayısı
 * - payment.alerts.open: admin'in henüz kapatmadığı fazla/geç ödeme uyarısı sayısı
 * Okunamazsa (RabbitMQ/veritabanı geçici kapalı) NaN döner; uyarı yanlışlıkla tetiklenmez.
 */
@Component
class PaymentHealthMetrics {
    PaymentHealthMetrics(MeterRegistry registry, DeadLetters deadLetters, PaymentAlertRepository alerts) {
        Gauge.builder("payment.events.dead_letters", () -> safely(deadLetters::count))
                .description("DLQ'da bekleyen ödeme olayları")
                .register(registry);
        Gauge.builder("payment.alerts.open", () -> safely(alerts::countByResolvedAtIsNull))
                .description("Kapatılmamış ödeme uyarıları")
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
