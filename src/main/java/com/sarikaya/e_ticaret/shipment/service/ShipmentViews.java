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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Siparişlere kargo görünümü ekler. Gönderiler ve ödeme durumları sayfa başına tek sorguyla yüklenir
 * (sipariş başına sorgu yok).
 *  - Yönetici: canShip = PREPARING + ödeme PAID + gönderi yok; gönderide failureReason (yalnız FAILED,
 *    sabit metin) ve canRetry (yalnız FAILED).
 *  - Müşteri (S1): yalnız CREATED / IN_TRANSIT / DELIVERED / DELIVERY_FAILED ve takip no doluyken
 *    gösterilir; REQUESTED, FAILED ya da takip nosuz gönderi null'dır.
 */
@Service
public class ShipmentViews {
    private static final Set<ShipmentStatus> VISIBLE_TO_CUSTOMER = Set.of(
            ShipmentStatus.CREATED, ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED, ShipmentStatus.DELIVERY_FAILED);

    private final OrderShipmentRepository shipments;
    private final PaymentRepository payments;
    private final TrackingUrls trackingUrls;

    public ShipmentViews(OrderShipmentRepository shipments, PaymentRepository payments, TrackingUrls trackingUrls) {
        this.shipments = shipments;
        this.payments = payments;
        this.trackingUrls = trackingUrls;
    }

    @Transactional(readOnly = true)
    public AdminOrderPageResponse adminPage(OrderPageResponse page) {
        return new AdminOrderPageResponse(admin(page.items()), page.page(), page.size(), page.totalItems(),
                page.totalPages());
    }

    @Transactional(readOnly = true)
    public AdminOrderResponse adminOne(OrderResponse order) {
        return admin(List.of(order)).getFirst();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> customer(List<OrderResponse> orders) {
        if (orders.isEmpty()) {
            return orders;
        }
        Map<Long, OrderShipment> byOrder = shipmentsOf(orders);
        return orders.stream().map(order -> order.withShipment(customerView(byOrder.get(order.id())))).toList();
    }

    private List<AdminOrderResponse> admin(List<OrderResponse> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, OrderShipment> byOrder = shipmentsOf(orders);
        Set<Long> paid = new HashSet<>(payments.findOrderIdsWithStatus(ids(orders), PaymentStatus.PAID));
        return orders.stream().map(order -> {
            OrderShipment shipment = byOrder.get(order.id());
            boolean canShip = shipment == null && order.status() == OrderStatus.PREPARING && paid.contains(order.id());
            return AdminOrderResponse.of(order, canShip, adminView(shipment));
        }).toList();
    }

    private Map<Long, OrderShipment> shipmentsOf(List<OrderResponse> orders) {
        return shipments.findAllByOrderIdIn(ids(orders)).stream()
                .collect(Collectors.toMap(OrderShipment::getOrderId, Function.identity()));
    }

    private static Set<Long> ids(List<OrderResponse> orders) {
        return orders.stream().map(OrderResponse::id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private AdminShipment adminView(OrderShipment shipment) {
        if (shipment == null) {
            return null;
        }
        boolean failed = shipment.getStatus() == ShipmentStatus.FAILED;
        return new AdminShipment(shipment.getStatus(), shipment.getCarrierName(), shipment.getTrackingNumber(),
                trackingUrls.urlFor(shipment.getCarrierCode(), shipment.getTrackingNumber()), shipment.getStatusAt(),
                shipment.getRequestNo(), failed ? FailureTexts.forCode(shipment.getFailureCode()) : null, failed);
    }

    private CustomerShipment customerView(OrderShipment shipment) {
        if (shipment == null || !VISIBLE_TO_CUSTOMER.contains(shipment.getStatus())
                || shipment.getTrackingNumber() == null || shipment.getTrackingNumber().isBlank()) {
            return null;
        }
        return new CustomerShipment(shipment.getStatus(), shipment.getCarrierName(), shipment.getTrackingNumber(),
                trackingUrls.urlFor(shipment.getCarrierCode(), shipment.getTrackingNumber()), shipment.getStatusAt());
    }
}
