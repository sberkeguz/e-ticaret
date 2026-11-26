package com.sarikaya.e_ticaret.shipment.messaging;

import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.events.MessageRetries;
import com.sarikaya.e_ticaret.payment.events.ProcessedMessages;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.shipment.entity.OrderShipment;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA: sırası karışık / sınırda olaylar. */
class ShipmentEventOrderingQaTest {
    private static final long ORDER_ID = 42L;
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-26T09:15:42.1234567+00:00");

    private final ProcessedMessages processed = mock(ProcessedMessages.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderShipmentRepository shipments = mock(OrderShipmentRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ShipmentEventHandler handler = new ShipmentEventHandler(processed, mock(PaymentRepository.class),
            orders, shipments, registry);
    private Order order;
    private OrderShipment shipment;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(ORDER_ID);
        order.setStatus(OrderStatus.PREPARING);
        shipment = new OrderShipment();
        shipment.setOrderId(ORDER_ID);
        shipment.setRequestNo(1);
        shipment.setStatus(ShipmentStatus.REQUESTED);
        shipment.setStatusAt(Instant.parse("2026-11-26T09:00:00Z"));
        when(processed.markProcessed(anyString(), anyString())).thenReturn(true);
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipments.findByOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.of(shipment));
    }

    @Test
    void deliveredThenLateCreatedOfSameRequestKeepsDelivered() {
        handler.handle("m-3", event(3, 1, "DELIVERED", "TK1"));
        handler.handle("m-1", event(1, 1, "CREATED", "TK1"));
        assertEquals(ShipmentStatus.DELIVERED, shipment.getStatus());
        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        assertEquals(3, shipment.getLastEventSequence());
    }

    @Test
    void deliveryFailedThenInTransitKeepsOrderShippedWithoutConflict() {
        handler.handle("m-1", event(1, 1, "CREATED", "TK1"));
        handler.handle("m-2", event(2, 1, "DELIVERY_FAILED", "TK1"));
        handler.handle("m-3", event(3, 1, "IN_TRANSIT", "TK1"));
        assertEquals(ShipmentStatus.IN_TRANSIT, shipment.getStatus());
        assertEquals(OrderStatus.SHIPPED, order.getStatus());
        assertEquals(0.0, registry.counter("shipment.events.order_conflicts").count());
    }

    @Test
    void eventsOfOldRequestAfterRetryAreIgnoredAndNewRequestContinuesSequence() {
        // İstek 1 FAILED (sıra 2), "Tekrar dene" → istek 2, sıra sıfırlanmaz (cargo-service de sıfırlamaz).
        handler.handle("m-2", event(2, 1, "FAILED", null));
        assertEquals(ShipmentStatus.FAILED, shipment.getStatus());
        shipment.setRequestNo(2);
        shipment.setStatus(ShipmentStatus.REQUESTED);

        handler.handle("m-old", event(9, 1, "CREATED", "TK-ESKI"));
        assertEquals(ShipmentStatus.REQUESTED, shipment.getStatus());

        handler.handle("m-3", event(3, 2, "CREATED", "TK-YENI"));
        assertEquals(ShipmentStatus.CREATED, shipment.getStatus());
        assertEquals("TK-YENI", shipment.getTrackingNumber());
        assertEquals(OrderStatus.SHIPPED, order.getStatus());
    }

    @Test
    void maxIntSequenceIsAppliedAndOverflowGoesToDeadLetters() {
        handler.handle("m-max", event(Integer.MAX_VALUE, 1, "CREATED", "TK1"));
        assertEquals(Integer.MAX_VALUE, shipment.getLastEventSequence());

        ShipmentEventHandler mockHandler = mock(ShipmentEventHandler.class);
        MessageRetries retries = mock(MessageRetries.class);
        ShipmentEventListener listener = new ShipmentEventListener(mockHandler, retries, JsonMapper.builder().build());
        String body = ShipmentEventListenerTest.CREATED_FROM_CARGO.replace("\"sequence\":1", "\"sequence\":2147483648");
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(UUID.randomUUID().toString());
        listener.onMessage(new Message(body.getBytes(StandardCharsets.UTF_8), properties));
        verify(mockHandler, never()).handle(any(), any());
        verify(retries).handleFailure(any(), any(), eq(false), eq(CargoTopology.EVENTS_ROUTE));
    }

    private static ShipmentEvent event(int sequence, int requestNo, String status, String tracking) {
        return new ShipmentEvent("m", "ShipmentEvent", 1, AT, null, sequence, new ShipmentEvent.Data(ORDER_ID,
                UUID.randomUUID(), requestNo, status, tracking == null ? null : "FAKE",
                tracking == null ? null : "Test Kargo", tracking, AT, null));
    }
}
