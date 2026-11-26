package com.sarikaya.e_ticaret.order.dto;

import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.shipment.dto.AdminShipment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Yöneticinin gördüğü sipariş (sözleşme 1.1): OrderResponse alanları (müşteri kargo alanı hariç) +
 * canShip (PREPARING + ödeme PAID + gönderi yok) + yönetici gönderi görünümü.
 */
public record AdminOrderResponse(Long id, String username, String firstName, String lastName, String phone,
                                 String city, String district, String neighborhood, String address, BigDecimal total,
                                 OrderStatus status, Instant createdAt, List<OrderResponse.Item> items,
                                 boolean paymentProcessing, boolean canShip, AdminShipment shipment) {

    public static AdminOrderResponse of(OrderResponse order, boolean canShip, AdminShipment shipment) {
        return new AdminOrderResponse(order.id(), order.username(), order.firstName(), order.lastName(), order.phone(),
                order.city(), order.district(), order.neighborhood(), order.address(), order.total(), order.status(),
                order.createdAt(), order.items(), order.paymentProcessing(), canShip, shipment);
    }
}
