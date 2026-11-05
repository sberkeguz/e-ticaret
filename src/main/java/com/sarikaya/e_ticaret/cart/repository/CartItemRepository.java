package com.sarikaya.e_ticaret.cart.repository;

import com.sarikaya.e_ticaret.cart.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByUser_IdOrderByIdAsc(Long userId);
}
