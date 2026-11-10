package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.order.dto.OrderRequest;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class OrderServiceStockTest {
    @Test
    void rejectsOutOfStockWithoutCreatingOrder() {
        OrderRepository orders = mock(OrderRepository.class);
        ProductRepository products = mock(ProductRepository.class);
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("buyer")).thenReturn(Optional.of(new User()));
        Product product = new Product();
        product.setName("Armut");
        product.setActive(true);
        product.setStock(0);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        OrderRequest request = new OrderRequest("Test", "Buyer", "05551234567",
                "Istanbul", "Kadikoy", "Moda", "Adres", List.of(new OrderRequest.Item(4L, 1)));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> new OrderService(orders, products, users).create("buyer", request));
        assertEquals("Armut için yeterli stok yok (kalan: 0).", error.getMessage());
        verifyNoInteractions(orders);
    }
}
