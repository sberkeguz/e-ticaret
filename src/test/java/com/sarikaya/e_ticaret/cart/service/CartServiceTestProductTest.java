package com.sarikaya.e_ticaret.cart.service;

import com.sarikaya.e_ticaret.cart.dto.CartRequest;
import com.sarikaya.e_ticaret.cart.entity.CartItem;
import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartServiceTestProductTest {

    private final CartItemRepository cartItemRepository = mock(CartItemRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final CartService cartService =
            new CartService(cartItemRepository, productRepository, userRepository, paymentRepository);

    @Test
    void customerCannotPutTestProductInCart() {
        User customer = user(Role.USER);
        mockTestProduct();

        assertThrows(ResourceNotFoundException.class,
                () -> cartService.replaceCart(customer, new CartRequest(List.of(new CartRequest.Item(9L, 1)))));

        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void adminCanPutTestProductInCart() {
        User admin = user(Role.ADMIN);
        mockTestProduct();
        List<CartItem> saved = new ArrayList<>();
        when(cartItemRepository.findByUser_IdOrderByIdAsc(1L)).thenAnswer(invocation -> saved);
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        var result = cartService.replaceCart(admin, new CartRequest(List.of(new CartRequest.Item(9L, 1))));

        assertEquals(1, result.size());
    }

    private User user(Role role) {
        User user = User.builder().id(1L).username("tester").role(role).build();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        return user;
    }

    private void mockTestProduct() {
        Product product = new Product();
        product.setId(9L);
        product.setName("Test elma");
        product.setPrice(new BigDecimal("5.00"));
        product.setStock(10);
        product.setTestProduct(true);
        when(productRepository.findByIdAndActiveTrue(9L)).thenReturn(Optional.of(product));
    }
}
