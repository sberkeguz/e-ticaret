package com.sarikaya.e_ticaret.cart.service;

import com.sarikaya.e_ticaret.cart.dto.CartItemResponse;
import com.sarikaya.e_ticaret.cart.dto.CartRequest;
import com.sarikaya.e_ticaret.cart.entity.CartItem;
import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<CartItemResponse> getCart(User user) {
        return cartItemRepository.findByUser_IdOrderByIdAsc(user.getId())
                .stream()
                .map(CartItemResponse::from)
                .toList();
    }

    @Transactional
    public List<CartItemResponse> replaceCart(User user, CartRequest request) {
        Map<Long, CartItem> existingByProduct = new HashMap<>();
        for (CartItem item : cartItemRepository.findByUser_IdOrderByIdAsc(user.getId())) {
            existingByProduct.put(item.getProduct().getId(), item);
        }

        Set<Long> requestedIds = new HashSet<>();
        for (CartRequest.Item requested : request.items()) {
            if (!requestedIds.add(requested.productId())) {
                throw new BadRequestException("Sepette aynı ürün birden fazla kez gönderilemez.");
            }

            Product product = productRepository.findByIdAndActiveTrue(requested.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("Sepetteki ürün bulunamadı."));
            if (requested.quantity() > product.getStock()) {
                throw new BadRequestException("Stok miktarı aşıldı: " + product.getName());
            }

            CartItem item = existingByProduct.remove(product.getId());
            if (item == null) {
                item = new CartItem();
                item.setUser(user);
                item.setProduct(product);
            }
            item.setQuantity(requested.quantity());
            cartItemRepository.save(item);
        }

        cartItemRepository.deleteAll(existingByProduct.values());
        return getCart(user);
    }
}
