package com.sarikaya.e_ticaret.payment.service;

import com.sarikaya.e_ticaret.cart.entity.CartItem;
import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.order.dto.OrderRequest;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.client.IyzicoServiceClient;
import com.sarikaya.e_ticaret.payment.dto.CheckoutRequest;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class PaymentConcurrencyTest {
    private static final String KEY = "6f1c2a8e-3b4d-4c5e-9f00-112233445566";
    @Autowired private PaymentService service;
    @Autowired private UserRepository users;
    @Autowired private ProductRepository products;
    @Autowired private CartItemRepository cartItems;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @MockitoBean private IyzicoServiceClient iyzicoService;

    @Test
    void simultaneousCheckoutsCreateOnlyOnePayment() throws Exception {
        User user = users.saveAndFlush(User.builder()
                .username("race-" + UUID.randomUUID()).password("test").role(Role.USER).build());
        Product product = new Product();
        product.setName("Concurrency test");
        product.setPrice(new BigDecimal("10.00"));
        product.setStock(10);
        product.setActive(true);
        product = products.saveAndFlush(product);
        CartItem cartItem = new CartItem();
        cartItem.setUser(user);
        cartItem.setProduct(product);
        cartItem.setQuantity(1);
        cartItems.saveAndFlush(cartItem);
        CheckoutRequest request = new CheckoutRequest("Test", "Buyer", "05551234567",
                "test@example.com", "11111111111", "Istanbul", "Kadikoy", "Moda", "Test",
                List.of(new OrderRequest.Item(product.getId(), 1)), false,
                new CheckoutRequest.Card("Test Buyer", "5528790000000008", "12", "2030", "123"));
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            when(iyzicoService.startPayment(any(), any(), anyBoolean())).thenAnswer(invocation -> {
                providerStarted.countDown();
                if (!releaseProvider.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Provider timeout");
                return new IyzicoServiceClient.AttemptResult(UUID.randomUUID(), null, "SUCCEEDED",
                        "test-" + UUID.randomUUID(), null, "t", null);
            });
            var first = executor.submit(() -> service.initialize(user.getUsername(), request, "127.0.0.1", KEY));
            try {
                assertTrue(providerStarted.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    return service.initialize(user.getUsername(), request, "127.0.0.1", KEY);
                });
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
                releaseProvider.countDown();
                assertNotNull(first.get(10, TimeUnit.SECONDS).result());
                ExecutionException error = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
                assertInstanceOf(BadRequestException.class, error.getCause());
                // İlk ödeme tamamlanınca sepet boşalır; ikinci istek eski sepetle reddedilir.
                assertTrue(error.getCause().getMessage().contains("Sepetiniz değişti"));
                assertEquals(9, products.findById(product.getId()).orElseThrow().getStock());
                assertEquals(1, orders.findAllByUserUsernameOrderByCreatedAtDesc(user.getUsername()).size());
            } finally {
                releaseProvider.countDown();
            }
        } finally {
            var userOrders = orders.findAllByUserUsernameOrderByCreatedAtDesc(user.getUsername());
            payments.deleteAll(payments.findAll().stream()
                    .filter(payment -> userOrders.stream().anyMatch(order -> order.getId().equals(payment.getOrder().getId())))
                    .toList());
            orders.deleteAll(userOrders);
            cartItems.deleteAll(cartItems.findByUser_IdOrderByIdAsc(user.getId()));
            products.deleteById(product.getId());
            users.deleteById(user.getId());
        }
    }
}
