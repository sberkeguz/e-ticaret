package com.sarikaya.e_ticaret.order.dto;

import java.math.BigDecimal;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;

import java.time.Instant;
import java.util.List;

public record OrderDetailResponse(
        Long id,
        Instant createdAt,
        OrderStatus orderStatus,
        /** Ödeme kaydı yoksa null. */
        PaymentStatus paymentStatus,
        BigDecimal total,
        BigDecimal paidAmount,
        String firstName,
        String lastName,
        String phone,
        String city,
        String district,
        String neighborhood,
        String address,
        List<Item> items) {

    public record Item(Long productId, String productName, String brand,
                       BigDecimal price, int quantity, BigDecimal lineTotal) {}
}
