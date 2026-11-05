package com.sarikaya.e_ticaret.cart.dto;

import com.sarikaya.e_ticaret.cart.entity.CartItem;

import java.math.BigDecimal;

public record CartItemResponse(
        Long productId,
        String name,
        BigDecimal price,
        String imageUrl,
        Integer stock,
        Integer quantity) {

    public static CartItemResponse from(CartItem item) {
        return new CartItemResponse(
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getProduct().getPrice(),
                item.getProduct().getImageUrl(),
                item.getProduct().getStock(),
                item.getQuantity());
    }
}
