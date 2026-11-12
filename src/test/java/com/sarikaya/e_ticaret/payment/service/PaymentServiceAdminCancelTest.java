package com.sarikaya.e_ticaret.payment.service;

import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.client.IyzicoServiceClient;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentServiceAdminCancelTest {
    private static final UUID ATTEMPT = UUID.fromString("0b6c1f7e-1111-4222-8333-944455566677");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CartItemRepository cartItems = mock(CartItemRepository.class);
    private final IyzicoServiceClient iyzicoService = mock(IyzicoServiceClient.class);

    private final PaymentService service = new PaymentService(
            orders, payments, products, users, cartItems, iyzicoService);

    private Order order;
    private Product product;

    @BeforeEach
    void setUp() {
        User user = User.builder().id(7L).username("buyer").build();

        order = new Order();
        order.setId(42L);
        order.setUser(user);
        order.setTotal(new BigDecimal("25.00"));
        order.setStatus("Hazırlanıyor");

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductId(4L);
        item.setProductName("Armut");
        item.setPrice(new BigDecimal("12.50"));
        item.setQuantity(2);
        order.getItems().add(item);

        product = new Product();
        product.setId(4L);
        product.setStock(3);

        when(orders.findById(42L)).thenReturn(Optional.of(order));
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
    }

    @Test
    void paidOrderIsRefundedAndStockIsReturned() {
        Payment payment = payment("PAID");

        service.cancelByAdmin(42L, "127.0.0.1");

        verify(iyzicoService).refund(ATTEMPT, "127.0.0.1");
        assertEquals("REFUNDED", payment.getStatus());
        assertEquals("İptal edildi", order.getStatus());
        assertEquals(5, product.getStock());
    }

    @Test
    void paymentFromBeforeIyzicoServiceIsRefundedByIyzicoPaymentId() {
        Payment payment = payment("PAID");
        payment.setAttemptId(null);

        service.cancelByAdmin(42L, "127.0.0.1");

        verify(iyzicoService).refundLegacy("iyzico-payment-1", new BigDecimal("25.00"), "127.0.0.1", 42L);
        assertEquals("REFUNDED", payment.getStatus());
    }

    @Test
    void failedRefundChangesNothing() {
        Payment payment = payment("PAID");
        when(iyzicoService.refund(ATTEMPT, "127.0.0.1")).thenThrow(new BadRequestException("İade süresi geçti"));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.cancelByAdmin(42L, "127.0.0.1"));

        assertEquals("İade süresi geçti", error.getMessage());
        assertEquals("PAID", payment.getStatus());
        assertEquals("Hazırlanıyor", order.getStatus());
        assertEquals(3, product.getStock());
    }

    @Test
    void pendingPaymentIsClosedWithoutRefund() {
        order.setStatus("Ödeme bekliyor");
        Payment payment = payment("PENDING");

        service.cancelByAdmin(42L, "127.0.0.1");

        verifyNoInteractions(iyzicoService);
        assertEquals("CANCELLED", payment.getStatus());
        assertEquals("İptal edildi", order.getStatus());
        assertEquals(5, product.getStock());
    }

    @Test
    void orderWithoutPaymentRecordOnlyReturnsStock() {
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.empty());

        service.cancelByAdmin(42L, "127.0.0.1");

        assertEquals("İptal edildi", order.getStatus());
        assertEquals(5, product.getStock());
    }

    @Test
    void expiredPaymentDoesNotReturnStockTwice() {
        order.setStatus("Ödeme süresi doldu");
        Payment payment = payment("EXPIRED");

        service.cancelByAdmin(42L, "127.0.0.1");

        assertEquals("EXPIRED", payment.getStatus());
        assertEquals("İptal edildi", order.getStatus());
        assertEquals(3, product.getStock());
    }

    @Test
    void alreadyCancelledOrderIsLeftAlone() {
        order.setStatus("İptal edildi");

        service.cancelByAdmin(42L, "127.0.0.1");

        verify(payments, never()).findByOrderIdForUpdate(any());
        assertEquals(3, product.getStock());
    }

    @Test
    void paidOrderWithoutIyzicoPaymentIdCannotBeRefunded() {
        Payment payment = payment("PAID");
        payment.setToken(null);
        payment.setAttemptId(null);

        assertThrows(BadRequestException.class, () -> service.cancelByAdmin(42L, "127.0.0.1"));

        verifyNoInteractions(iyzicoService);
        assertEquals("PAID", payment.getStatus());
        assertEquals(3, product.getStock());
    }

    private Payment payment(String status) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setStatus(status);
        payment.setToken("iyzico-payment-1");
        payment.setAttemptId(ATTEMPT);
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        return payment;
    }
}
