package com.sarikaya.e_ticaret.order.dto;

import java.math.BigDecimal;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.shipment.dto.CustomerShipment;

import java.time.Instant;
import java.util.List;

public record OrderResponse(Long id, String username, String firstName, String lastName, String phone,
                            String city, String district, String neighborhood, String address, BigDecimal total,
                            OrderStatus status, Instant createdAt, List<Item> items,
                            /** Ödeme isteği gönderildi, sonucu bekleniyor: müşteri tekrar ödeyemez/iptal edemez. */
                            boolean paymentProcessing,
                            /** Müşteriye gösterilen kargo bilgisi; yoksa ya da henüz gösterilecek durumda değilse null. */
                            CustomerShipment shipment) {

    public record Item(Long productId, String productName, BigDecimal price, int quantity) {}

    public OrderResponse withShipment(CustomerShipment value) {
        return new OrderResponse(id, username, firstName, lastName, phone, city, district, neighborhood, address,
                total, status, createdAt, items, paymentProcessing, value);
    }
}
