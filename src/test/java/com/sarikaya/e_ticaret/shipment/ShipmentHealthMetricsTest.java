package com.sarikaya.e_ticaret.shipment;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.sarikaya.e_ticaret.payment.events.PaymentBroker;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kargo sağlık ölçümleri: iki DLQ'nun boyu ve 30 dakikadan uzun REQUESTED'da bekleyen gönderiler. */
class ShipmentHealthMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PaymentBroker broker = mock(PaymentBroker.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final Channel channel = mock(Channel.class);
    private final OrderShipmentRepository shipments = mock(OrderShipmentRepository.class);

    ShipmentHealthMetricsTest() throws Exception {
        when(rabbit.execute(any())).thenAnswer(invocation -> {
            ChannelCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(channel);
        });
        AMQP.Queue.DeclareOk events = declareOk(3);
        AMQP.Queue.DeclareOk commands = declareOk(5);
        when(channel.queueDeclarePassive("backend.shipment-events.dlq")).thenReturn(events);
        when(channel.queueDeclarePassive("cargo.shipment-requests.dlq")).thenReturn(commands);
        new ShipmentHealthMetrics(registry, broker, rabbit, shipments);
    }

    @Test
    void deadLetterQueuesAreMeasuredWhenBrokerIsUp() {
        when(broker.isUp()).thenReturn(true);

        assertEquals(3.0, gauge("shipment.events.dead_letters"));
        assertEquals(5.0, gauge("shipment.commands.dead_letters"));
    }

    @Test
    void brokerDownGivesNoValueWithoutAsking() {
        when(broker.isUp()).thenReturn(false);

        assertTrue(Double.isNaN(gauge("shipment.events.dead_letters")));
        assertTrue(Double.isNaN(gauge("shipment.commands.dead_letters")));
        verify(rabbit, never()).execute(any());
    }

    @Test
    void stuckRequestsAreThoseRequestedOverThirtyMinutesAgo() {
        when(shipments.countByStatusAndStatusAtBefore(eq(ShipmentStatus.REQUESTED), any())).thenReturn(2L);

        Instant before = Instant.now();
        assertEquals(2.0, gauge("shipment.requests.stuck"));

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(shipments).countByStatusAndStatusAtBefore(eq(ShipmentStatus.REQUESTED), cutoff.capture());
        Duration age = Duration.between(cutoff.getValue(), before);
        assertTrue(age.compareTo(Duration.ofMinutes(29)) > 0 && age.compareTo(Duration.ofMinutes(31)) < 0, age::toString);
    }

    @Test
    void databaseFailureGivesNoValue() {
        when(shipments.countByStatusAndStatusAtBefore(any(), any())).thenThrow(new IllegalStateException("db yok"));

        assertTrue(Double.isNaN(gauge("shipment.requests.stuck")));
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private static AMQP.Queue.DeclareOk declareOk(int messages) {
        AMQP.Queue.DeclareOk ok = mock(AMQP.Queue.DeclareOk.class);
        when(ok.getMessageCount()).thenReturn(messages);
        return ok;
    }
}
