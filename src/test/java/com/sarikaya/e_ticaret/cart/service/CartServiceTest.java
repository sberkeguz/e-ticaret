package com.sarikaya.e_ticaret.cart.service;

import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.cart.dto.CartRequest;
import com.sarikaya.e_ticaret.cart.entity.CartItem;
import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import com.sarikaya.e_ticaret.exception.PaymentInProgressException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartServiceTest {

    private final CartItemRepository cartItemRepository = mock(CartItemRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final CartService cartService = new CartService(cartItemRepository, productRepository, userRepository, paymentRepository);

    @Test
    void replaceCartStoresRequestedQuantitiesAndReturnsProductDetails() {
        User user = User.builder().id(1L).username("tester").build();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        Product product = new Product();
        product.setId(7L);
        product.setName("Elma");
        product.setPrice(new java.math.BigDecimal("10.00"));
        product.setStock(5);
        List<CartItem> savedItems = new ArrayList<>();
        when(cartItemRepository.findByUser_IdOrderByIdAsc(1L)).thenAnswer(invocation -> savedItems);
        when(productRepository.findByIdAndActiveTrue(7L)).thenReturn(Optional.of(product));
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(invocation -> {
            CartItem item = invocation.getArgument(0);
            savedItems.add(item);
            return item;
        });

        var result = cartService.replaceCart(user, new CartRequest(List.of(new CartRequest.Item(7L, 3))));

        assertEquals(1, result.size());
        assertEquals(7L, result.getFirst().productId());
        assertEquals(3, result.getFirst().quantity());
        verify(cartItemRepository).save(any(CartItem.class));
    }

    @Test
    void replaceCartDropsSoldOutProducts() {
        User user = User.builder().id(1L).username("tester").build();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        Product product = new Product();
        product.setId(7L);
        product.setName("Elma");
        product.setStock(0);
        CartItem existing = new CartItem();
        existing.setProduct(product);
        existing.setQuantity(1);
        when(cartItemRepository.findByUser_IdOrderByIdAsc(1L)).thenReturn(List.of(existing));
        when(productRepository.findByIdAndActiveTrue(7L)).thenReturn(Optional.of(product));

        cartService.replaceCart(user, new CartRequest(List.of(new CartRequest.Item(7L, 3))));

        verify(cartItemRepository, org.mockito.Mockito.never()).save(any(CartItem.class));
        verify(cartItemRepository).deleteAll(org.mockito.ArgumentMatchers.<Iterable<CartItem>>argThat(items -> {
            List<CartItem> deleted = new ArrayList<>();
            items.forEach(deleted::add);
            return deleted.equals(List.of(existing));
        }));
    }

    @Test
    void replaceCartCapsQuantityToStock() {
        User user = User.builder().id(1L).username("tester").build();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        Product product = new Product();
        product.setId(7L);
        product.setName("Elma");
        product.setStock(2);
        when(cartItemRepository.findByUser_IdOrderByIdAsc(1L)).thenReturn(List.of());
        when(productRepository.findByIdAndActiveTrue(7L)).thenReturn(Optional.of(product));

        cartService.replaceCart(user, new CartRequest(List.of(new CartRequest.Item(7L, 5))));

        verify(cartItemRepository).save(org.mockito.ArgumentMatchers.argThat(item -> item.getQuantity() == 2));
    }

    @Test
    void replaceCartRejectsChangesDuringPayment() {
        User user = User.builder().id(1L).username("tester").build();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(paymentRepository.existsByOrder_User_IdAndStatusIn(1L, List.of(PaymentStatus.PENDING))).thenReturn(true);
        assertThrows(PaymentInProgressException.class, () -> cartService.replaceCart(user, new CartRequest(List.of())));
        org.mockito.Mockito.verifyNoInteractions(cartItemRepository, productRepository);
    }
}
