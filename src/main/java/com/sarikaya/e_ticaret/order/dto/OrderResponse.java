package com.sarikaya.e_ticaret.order.dto;

import java.math.BigDecimal;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;

import java.time.Instant;
import java.util.List;

public record OrderResponse(Long id, String username, String firstName, String lastName, String phone,
                            String city, String district, String neighborhood, String address, BigDecimal total,
                            OrderStatus status, Instant createdAt, List<Item> items) {

    public record Item(Long productId, String productName, BigDecimal price, int quantity) {}
}