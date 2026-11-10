package com.sarikaya.e_ticaret.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(
        Long id,
        LocalDateTime createdAt,
        String orderStatus,
        String paymentStatus,
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
