package com.sarikaya.e_ticaret.shipment.service;

import com.sarikaya.e_ticaret.order.dto.AdminOrderPageResponse;
import com.sarikaya.e_ticaret.order.dto.AdminOrderResponse;
import com.sarikaya.e_ticaret.order.dto.OrderPageResponse;
import com.sarikaya.e_ticaret.order.dto.OrderResponse;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.shipment.dto.AdminShipment;
import com.sarikaya.e_ticaret.shipment.dto.CustomerShipment;
import com.sarikaya.e_ticaret.shipment.entity.OrderShipment;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShipmentViewsTest {
    private static final Instant AT = Instant.parse("2026-11-26T10:00:00Z");

    private final OrderShipmentRepository shipments = mock(OrderShipmentRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ShipmentViews views = new ShipmentViews(shipments, payments,
            new TrackingUrls(Map.of("YURTICI", "https://kargo.example.com/t/{trackingNumber}")));

    @Test
    void canShipOnlyWhenPreparingPaidAndNoShipment() {
        when(payments.findOrderIdsWithStatus(anyCollection(), eq(PaymentStatus.PAID))).thenReturn(List.of(1L, 2L, 4L));
        when(shipments.findAllByOrderIdIn(anyCollection())).thenReturn(List.of(shipment(2L, ShipmentStatus.FAILED)));

        AdminOrderPageResponse page = views.adminPage(new OrderPageResponse(List.of(
                order(1L, OrderStatus.PREPARING), // ödendi, gönderi yok → true
                order(2L, OrderStatus.PREPARING), // gönderi var (FAILED) → false
                order(3L, OrderStatus.PREPARING), // ödenmedi → false
                order(4L, OrderStatus.SHIPPED)),  // hazırlanmıyor → false
                0, 50, 4, 1));

        assertEquals(List.of(true, false, false, false), page.items().stream().map(AdminOrderResponse::canShip).toList());
        assertEquals(0, page.page());
        assertEquals(4, page.totalItems());
    }

    @Test
    void shipmentsAndPaymentsAreLoadedOnceForTheWholePage() {
        when(payments.findOrderIdsWithStatus(anyCollection(), eq(PaymentStatus.PAID))).thenReturn(List.of());
        when(shipments.findAllByOrderIdIn(anyCollection())).thenReturn(List.of());

        views.adminPage(new OrderPageResponse(List.of(order(1L, OrderStatus.PREPARING), order(2L, OrderStatus.PREPARING),
                order(3L, OrderStatus.PREPARING)), 0, 50, 3, 1));

        verify(shipments, times(1)).findAllByOrderIdIn(eq(new HashSet<>(List.of(1L, 2L, 3L))));
        verify(payments, times(1)).findOrderIdsWithStatus(anyCollection(), eq(PaymentStatus.PAID));
    }

    @Test
    void emptyPageDoesNotQuery() {
        AdminOrderPageResponse page = views.adminPage(new OrderPageResponse(List.of(), 0, 50, 0, 0));

        assertTrue(page.items().isEmpty());
        verify(shipments, never()).findAllByOrderIdIn(any());
        verify(payments, never()).findOrderIdsWithStatus(any(), any());
    }

    @Test
    void failedShipmentHasFixedReasonAndCanBeRetried() {
        OrderShipment carrierFailed = shipment(1L, ShipmentStatus.FAILED);
        carrierFailed.setFailureCode(OrderShipment.CARRIER_FAILED);
        OrderShipment unpaid = shipment(2L, ShipmentStatus.FAILED);
        unpaid.setFailureCode(OrderShipment.PAYMENT_NOT_CONFIRMED);
        OrderShipment created = shipment(3L, ShipmentStatus.CREATED);
        created.setCarrierCode("YURTICI");
        created.setCarrierName("Yurtiçi");
        created.setTrackingNumber("T 1");
        when(shipments.findAllByOrderIdIn(anyCollection())).thenReturn(List.of(carrierFailed, unpaid, created));
        when(payments.findOrderIdsWithStatus(anyCollection(), eq(PaymentStatus.PAID))).thenReturn(List.of(1L, 2L, 3L));

        List<AdminOrderResponse> items = views.adminPage(new OrderPageResponse(List.of(order(1L, OrderStatus.PREPARING),
                order(2L, OrderStatus.PREPARING), order(3L, OrderStatus.SHIPPED)), 0, 50, 3, 1)).items();

        AdminShipment first = items.get(0).shipment();
        assertEquals("Kargo firması gönderiyi oluşturamadı.", first.failureReason());
        assertTrue(first.canRetry());
        assertEquals("Kargo servisi ödemeyi onaylı görmedi; ödemeyi kontrol edip tekrar deneyin.",
                items.get(1).shipment().failureReason());
        AdminShipment third = items.get(2).shipment();
        assertNull(third.failureReason());
        assertFalse(third.canRetry());
        assertEquals("T 1", third.trackingNumber());
        assertEquals("Yurtiçi", third.carrierName());
        assertEquals("https://kargo.example.com/t/T%201", third.trackingUrl());
        assertEquals(1, third.requestNo());
        assertEquals(AT, third.statusAt());
    }

    @Test
    void customerSeesOnlyShipmentsWithTrackingNumber() {
        OrderShipment requested = shipment(1L, ShipmentStatus.REQUESTED);
        OrderShipment failed = shipment(2L, ShipmentStatus.FAILED);
        failed.setTrackingNumber("SHOULD-NOT-SHOW");
        OrderShipment inTransit = shipment(3L, ShipmentStatus.IN_TRANSIT);
        inTransit.setTrackingNumber("TRK-3");
        inTransit.setCarrierName("Test Kargo");
        inTransit.setCarrierCode("FAKE");
        OrderShipment createdWithoutNumber = shipment(4L, ShipmentStatus.CREATED);
        when(shipments.findAllByOrderIdIn(anyCollection())).thenReturn(List.of(requested, failed, inTransit, createdWithoutNumber));

        List<OrderResponse> result = views.customer(List.of(order(1L, OrderStatus.PREPARING), order(2L, OrderStatus.PREPARING),
                order(3L, OrderStatus.SHIPPED), order(4L, OrderStatus.SHIPPED), order(5L, OrderStatus.PREPARING)));

        assertNull(result.get(0).shipment());
        assertNull(result.get(1).shipment());
        CustomerShipment shown = result.get(2).shipment();
        assertNotNull(shown);
        assertEquals(ShipmentStatus.IN_TRANSIT, shown.status());
        assertEquals("TRK-3", shown.trackingNumber());
        assertEquals("Test Kargo", shown.carrierName());
        assertNull(shown.trackingUrl(), "FAKE firmasının bağlantısı yok");
        assertNull(result.get(3).shipment());
        assertNull(result.get(4).shipment());
        verify(payments, never()).findOrderIdsWithStatus(any(), any());
    }

    @Test
    void adminOneUsesSameRules() {
        when(shipments.findAllByOrderIdIn(anyCollection())).thenReturn(List.of());
        when(payments.findOrderIdsWithStatus(anyCollection(), eq(PaymentStatus.PAID))).thenReturn(List.of(9L));

        AdminOrderResponse one = views.adminOne(order(9L, OrderStatus.PREPARING));

        assertTrue(one.canShip());
        assertNull(one.shipment());
        assertEquals(9L, one.id());
    }

    private static OrderResponse order(long id, OrderStatus status) {
        return new OrderResponse(id, "u", "Ad", "Soyad", "0555", "İl", "İlçe", "Mah", "Adres",
                new BigDecimal("10.00"), status, AT, List.of(), false, null);
    }

    private static OrderShipment shipment(long orderId, ShipmentStatus status) {
        OrderShipment s = new OrderShipment();
        s.setOrderId(orderId);
        s.setRequestNo(1);
        s.setStatus(status);
        s.setStatusAt(AT);
        return s;
    }
}
