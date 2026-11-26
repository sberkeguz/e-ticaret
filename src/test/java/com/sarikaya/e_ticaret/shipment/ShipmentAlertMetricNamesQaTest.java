package com.sarikaya.e_ticaret.shipment;

import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.events.PaymentBroker;
import com.sarikaya.e_ticaret.payment.events.ProcessedMessages;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.shipment.messaging.ShipmentEventHandler;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * QA: Grafana uyarılarındaki kargo ölçüm adları (docker/grafana/provisioning/e-ticaret-alerts.yaml) kodda
 * kayıtlı ölçümlerin OTLP → Prometheus adıyla birebir eşleşir: nokta → alt çizgi, birimsiz gauge ek almaz,
 * sayaç "_total" alır.
 */
class ShipmentAlertMetricNamesQaTest {

    @Test
    void everyShipmentMetricInAlertsExistsInCode() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ShipmentHealthMetrics(registry, mock(PaymentBroker.class), mock(RabbitTemplate.class),
                mock(OrderShipmentRepository.class));
        new ShipmentEventHandler(mock(ProcessedMessages.class), mock(PaymentRepository.class),
                mock(OrderRepository.class), mock(OrderShipmentRepository.class), registry);

        Set<String> exported = new HashSet<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName().replace('.', '_');
            exported.add(meter.getId().getType() == Meter.Type.COUNTER ? name + "_total" : name);
        }

        String alerts = Files.readString(Path.of("docker", "grafana", "provisioning", "e-ticaret-alerts.yaml"));
        Matcher matcher = Pattern.compile("\\b(shipment_[a-z_]+)\\{").matcher(alerts);
        Set<String> used = new TreeSet<>();
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        assertEquals(Set.of("shipment_commands_dead_letters", "shipment_events_dead_letters",
                "shipment_events_order_conflicts_total", "shipment_requests_stuck"), used);
        for (String name : used) {
            assertTrue(exported.contains(name), name + " kodda yok; kayıtlı: " + exported);
        }
    }
}
