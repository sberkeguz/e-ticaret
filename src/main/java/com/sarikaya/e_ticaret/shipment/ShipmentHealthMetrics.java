package com.sarikaya.e_ticaret.shipment;

import com.sarikaya.e_ticaret.payment.events.PaymentBroker;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.messaging.CargoTopology;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * Kargo sağlık ölçümleri (uyarılar Grafana'da):
 *  - shipment.events.dead_letters   : backend.shipment-events.dlq'da bekleyen (uygulanamamış) gönderi olayları
 *  - shipment.commands.dead_letters : cargo.shipment-requests.dlq'da bekleyen (cargo-service'in reddettiği) komutlar
 *  - shipment.requests.stuck        : 30 dakikadan uzun süredir REQUESTED'da bekleyen gönderiler
 * shipment.events.order_conflicts sayacı ShipmentEventHandler'dadır.
 * RabbitMQ kapalıyken kuyruklar sorulmaz (NaN): bağlantı denemesi asılı kalıp tüm ölçümleri geciktirirdi.
 * Okuma hatasında da NaN döner.
 */
@Component
public class ShipmentHealthMetrics {
    static final Duration STUCK_AFTER = Duration.ofMinutes(30);

    public ShipmentHealthMetrics(MeterRegistry registry, PaymentBroker broker, RabbitTemplate rabbit,
                                 OrderShipmentRepository shipments) {
        Gauge.builder("shipment.events.dead_letters",
                        () -> broker.isUp() ? safely(() -> messageCount(rabbit, CargoTopology.EVENTS_DEAD_LETTER_QUEUE)) : Double.NaN)
                .description("DLQ'da bekleyen gönderi olayları")
                .register(registry);
        Gauge.builder("shipment.commands.dead_letters",
                        () -> broker.isUp() ? safely(() -> messageCount(rabbit, CargoTopology.SHIPMENT_REQUESTS_DEAD_LETTER_QUEUE)) : Double.NaN)
                .description("cargo-service'in reddedip DLQ'ya attığı kargo komutları")
                .register(registry);
        Gauge.builder("shipment.requests.stuck",
                        () -> safely(() -> shipments.countByStatusAndStatusAtBefore(
                                ShipmentStatus.REQUESTED, Instant.now().minus(STUCK_AFTER))))
                .description("30 dakikadan uzun süredir kargo servisinden sonuç bekleyen gönderiler")
                .register(registry);
    }

    private static long messageCount(RabbitTemplate rabbit, String queue) {
        Long count = rabbit.execute(channel -> (long) channel.queueDeclarePassive(queue).getMessageCount());
        return count == null ? 0 : count;
    }

    private static double safely(LongSupplier read) {
        try {
            return read.getAsLong();
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }
}
