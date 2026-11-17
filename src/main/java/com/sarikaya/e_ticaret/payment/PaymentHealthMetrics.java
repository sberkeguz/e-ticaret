package com.sarikaya.e_ticaret.payment;

import com.sarikaya.e_ticaret.payment.alerts.PaymentAlertRepository;
import com.sarikaya.e_ticaret.payment.events.DeadLetters;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

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
