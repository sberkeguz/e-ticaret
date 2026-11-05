package com.sarikaya.e_ticaret.cart.service;

import com.sarikaya.e_ticaret.cart.dto.CartRequest;
import com.sarikaya.e_ticaret.cart.entity.CartItem;
import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartServiceTest {

    private final CartItemRepository cartItemRepository = mock(CartItemRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final CartService cartService = new CartService(cartItemRepository, productRepository);

    @Test
    void replaceCartStoresRequestedQuantitiesAndReturnsProductDetails() {
        User user = User.builder().id(1L).username("tester").build();
        Product product = new Product();
        product.setId(7L);
        product.setName("Elma");
        product.setStock(5);
        when(cartItemRepository.findByUser_IdOrderByIdAsc(1L)).thenReturn(List.of());
        when(productRepository.findByIdAndActiveTrue(7L)).thenReturn(Optional.of(product));
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = cartService.replaceCart(user, new CartRequest(List.of(new CartRequest.Item(7L, 3))));

        assertEquals(1, result.size());
        assertEquals(7L, result.getFirst().productId());
        assertEquals(3, result.getFirst().quantity());
        verify(cartItemRepository).save(any(CartItem.class));
    }

    @Test
    void replaceCartRejectsQuantityAboveStockWithoutSaving() {
        User user = User.builder().id(1L).username("tester").build();
        Product product = new Product();
        product.setId(7L);
        product.setName("Elma");
        product.setStock(2);
        when(cartItemRepository.findByUser_IdOrderByIdAsc(1L)).thenReturn(List.of());
        when(productRepository.findByIdAndActiveTrue(7L)).thenReturn(Optional.of(product));

        assertThrows(BadRequestException.class, () -> cartService.replaceCart(
                user, new CartRequest(List.of(new CartRequest.Item(7L, 3)))));
        verify(cartItemRepository, never()).save(any(CartItem.class));
    }
}
