package com.sarikaya.e_ticaret.cart.controller;

import com.sarikaya.e_ticaret.cart.dto.CartItemResponse;
import com.sarikaya.e_ticaret.cart.dto.CartRequest;
import com.sarikaya.e_ticaret.cart.service.CartService;
import com.sarikaya.e_ticaret.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public List<CartItemResponse> getCart(@AuthenticationPrincipal User user) {
        return cartService.getCart(user);
    }

    @PutMapping
    public List<CartItemResponse> replaceCart(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CartRequest request) {
        return cartService.replaceCart(user, request);
    }
}
