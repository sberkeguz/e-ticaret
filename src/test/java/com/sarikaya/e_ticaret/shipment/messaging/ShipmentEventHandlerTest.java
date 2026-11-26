package com.sarikaya.e_ticaret.shipment.messaging;

import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.events.ProcessedMessages;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.shipment.entity.OrderShipment;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShipmentEventHandlerTest {
    private static final long ORDER_ID = 42L;
    private static final UUID CARGO_ID = UUID.fromString("019ac0e2-5f00-7aaa-8bbb-ccccdddd0001");
    private static final OffsetDateTime STATUS_AT = OffsetDateTime.parse("2026-11-26T09:15:42.1234567+00:00");

    private final ProcessedMessages processed = mock(ProcessedMessages.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderShipmentRepository shipments = mock(OrderShipmentRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ShipmentEventHandler handler = new ShipmentEventHandler(processed, payments, orders, shipments, registry);

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
        when(processed.markProcessed("m-1", "ShipmentCreated")).thenReturn(true);
        when(payments.findByOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipments.findByOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.of(shipment));
    }

    @Test
    void createdEventShipsPreparingOrderAndStoresTracking() {
        handler.handle("m-1", event("ShipmentCreated", 1, 1, "CREATED", "TK0000000001"));

        assertEquals(ShipmentStatus.CREATED, shipment.getStatus());
        assertEquals("TK0000000001", shipment.getTrackingNumber());
        assertEquals("FAKE", shipment.getCarrierCode());
        assertEquals("Test Kargo", shipment.getCarrierName());
        assertEquals(CARGO_ID, shipment.getCargoShipmentId());
        assertEquals(STATUS_AT.toInstant(), shipment.getStatusAt());
        assertEquals(1, shipment.getLastEventSequence());
        assertNull(shipment.getFailureCode());
        assertEquals(OrderStatus.SHIPPED, order.getStatus());
    }

    @Test
    void locksPaymentThenOrderThenShipment() {
        handler.handle("m-1", event("ShipmentCreated", 1, 1, "CREATED", "TK1"));

        InOrder inOrder = inOrder(processed, payments, orders, shipments);
        inOrder.verify(processed).markProcessed("m-1", "ShipmentCreated");
        inOrder.verify(payments).findByOrderIdForUpdate(ORDER_ID);
        inOrder.verify(orders).findByIdForUpdate(ORDER_ID);
        inOrder.verify(shipments).findByOrderIdForUpdate(ORDER_ID);
    }

    @Test
    void duplicateMessageIsSkipped() {
        when(processed.markProcessed("m-1", "ShipmentCreated")).thenReturn(false);

        handler.handle("m-1", event("ShipmentCreated", 1, 1, "CREATED", "TK1"));

        verifyNoInteractions(payments, orders, shipments);
        assertEquals(ShipmentStatus.REQUESTED, shipment.getStatus());
    }

    @Test
    void olderOrSameSequenceIsSkipped() {
        shipment.setStatus(ShipmentStatus.IN_TRANSIT);
        shipment.setTrackingNumber("TK1");
        shipment.setLastEventSequence(3);
        order.setStatus(OrderStatus.SHIPPED);
        when(processed.markProcessed("m-2", "ShipmentCreated")).thenReturn(true);

        handler.handle("m-2", event("ShipmentCreated", 2, 1, "CREATED", "TK1"));
        handler.handle("m-1", event("ShipmentCreated", 3, 1, "CREATED", "TK1"));

        assertEquals(ShipmentStatus.IN_TRANSIT, shipment.getStatus());
        assertEquals(3, shipment.getLastEventSequence());
    }

    @Test
    void laterEventArrivingFirstIsAppliedAsSnapshot() {
        // CREATED (1) kayboldu ya da gecikti; IN_TRANSIT (2) takip numarasıyla birlikte gelir.
        when(processed.markProcessed("m-1", "ShipmentInTransit")).thenReturn(true);

        handler.handle("m-1", event("ShipmentInTransit", 2, 1, "IN_TRANSIT", "TK9"));

        assertEquals(ShipmentStatus.IN_TRANSIT, shipment.getStatus());
        assertEquals("TK9", shipment.getTrackingNumber());
        assertEquals(OrderStatus.SHIPPED, order.getStatus());
    }

    @Test
    void deliveredEventDeliversShippedOrPreparingOrder() {
        when(processed.markProcessed("m-1", "ShipmentDelivered")).thenReturn(true);
        order.setStatus(OrderStatus.SHIPPED);

        handler.handle("m-1", event("ShipmentDelivered", 4, 1, "DELIVERED", "TK1"));

        assertEquals(ShipmentStatus.DELIVERED, shipment.getStatus());
        assertEquals(OrderStatus.DELIVERED, order.getStatus());

        order.setStatus(OrderStatus.PREPARING);
        when(processed.markProcessed("m-3", "ShipmentDelivered")).thenReturn(true);
        handler.handle("m-3", event("ShipmentDelivered", 5, 1, "DELIVERED", "TK1"));
        assertEquals(OrderStatus.DELIVERED, order.getStatus());
    }

    @Test
    void deliveryFailedKeepsOrderShipped() {
        when(processed.markProcessed("m-1", "ShipmentDeliveryFailed")).thenReturn(true);
        order.setStatus(OrderStatus.SHIPPED);

        handler.handle("m-1", event("ShipmentDeliveryFailed", 3, 1, "DELIVERY_FAILED", "TK1"));

        assertEquals(ShipmentStatus.DELIVERY_FAILED, shipment.getStatus());
        assertEquals(OrderStatus.SHIPPED, order.getStatus());
    }

    @Test
    void carrierFailureMarksShipmentFailedAndOrderStaysPreparing() {
        when(processed.markProcessed("m-1", "ShipmentFailed")).thenReturn(true);

        handler.handle("m-1", event("ShipmentFailed", 1, 1, "FAILED", null, "Firma: adres geçersiz (ham metin)"));

        assertEquals(ShipmentStatus.FAILED, shipment.getStatus());
        assertEquals(OrderShipment.CARRIER_FAILED, shipment.getFailureCode());
        assertEquals(OrderStatus.PREPARING, order.getStatus());
    }

    @Test
    void rejectionMarksShipmentFailedWithPaymentNotConfirmed() {
        when(processed.markProcessed("m-1", "ShipmentFailed")).thenReturn(true);

        handler.handle("m-1", event("ShipmentFailed", 1, 1, "REJECTED", null, "Ödemesi onaylanmamış sipariş kargoya verilemez."));

        assertEquals(ShipmentStatus.FAILED, shipment.getStatus());
        assertEquals(OrderShipment.PAYMENT_NOT_CONFIRMED, shipment.getFailureCode());
        assertEquals(OrderStatus.PREPARING, order.getStatus());
    }

    @Test
    void eventOfOlderRequestIsSkipped() {
        shipment.setRequestNo(2);
        shipment.setLastEventSequence(1);
        when(processed.markProcessed("m-1", "ShipmentFailed")).thenReturn(true);

        handler.handle("m-1", event("ShipmentFailed", 2, 1, "FAILED", null));

        assertEquals(ShipmentStatus.REQUESTED, shipment.getStatus());
        assertEquals(1, shipment.getLastEventSequence());
    }

    @Test
    void eventOfNewerRequestThanBackendKnowsGoesToDeadLetters() {
        ShipmentEventException error = assertThrows(ShipmentEventException.class,
                () -> handler.handle("m-1", event("ShipmentCreated", 1, 2, "CREATED", "TK1")));

        assertFalse(error.retryable());
        assertEquals(ShipmentStatus.REQUESTED, shipment.getStatus());
    }

    @Test
    void unknownOrderGoesToDeadLetters() {
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        ShipmentEventException error = assertThrows(ShipmentEventException.class,
                () -> handler.handle("m-1", event("ShipmentCreated", 1, 1, "CREATED", "TK1")));

        assertFalse(error.retryable());
    }

    @Test
    void orderWithoutShipmentGoesToDeadLetters() {
        when(shipments.findByOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        ShipmentEventException error = assertThrows(ShipmentEventException.class,
                () -> handler.handle("m-1", event("ShipmentCreated", 1, 1, "CREATED", "TK1")));

        assertFalse(error.retryable());
        assertEquals(OrderStatus.PREPARING, order.getStatus());
    }

    @Test
    void unexpectedStatusOrTooLongFieldGoesToDeadLetters() {
        assertFalse(assertThrows(ShipmentEventException.class,
                () -> handler.handle("m-1", event("ShipmentCreated", 1, 1, "SENDING", "TK1"))).retryable());
        assertFalse(assertThrows(ShipmentEventException.class,
                () -> handler.handle("m-1", event("ShipmentCreated", 1, 1, "CREATED", "T".repeat(101)))).retryable());
        assertEquals(ShipmentStatus.REQUESTED, shipment.getStatus());
        assertEquals(OrderStatus.PREPARING, order.getStatus());
    }

    @Test
    void cancelledOrderKeepsStatusButShipmentIsRecordedAndConflictCounted() {
        order.setStatus(OrderStatus.CANCELLED);

        handler.handle("m-1", event("ShipmentCreated", 1, 1, "CREATED", "TK1"));

        assertEquals(ShipmentStatus.CREATED, shipment.getStatus());
        assertEquals("TK1", shipment.getTrackingNumber());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(1.0, registry.counter("shipment.events.order_conflicts").count());
    }

    private static ShipmentEvent event(String type, int sequence, int requestNo, String status, String tracking) {
        return event(type, sequence, requestNo, status, tracking, null);
    }

    private static ShipmentEvent event(String type, int sequence, int requestNo, String status, String tracking,
                                       String failureReason) {
        return new ShipmentEvent("m-1", type, 1, STATUS_AT, "4bf92f3577b34da6a3ce929d0e0e4736", sequence,
                new ShipmentEvent.Data(ORDER_ID, CARGO_ID, requestNo, status, "FAKE", "Test Kargo", tracking,
                        STATUS_AT, failureReason));
    }
}
