package com.sarikaya.e_ticaret.payment.service;

import com.iyzipay.model.Refund;
import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PaymentServiceAdminCancelTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CartItemRepository cartItems = mock(CartItemRepository.class);

    private final PaymentService service = new PaymentService(
            orders, payments, products, users, cartItems, "api-key", "secret",
            "https://sandbox-api.iyzipay.com", "https://example.test/api/payments/callback", "https://example.test");

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
        Refund refund = mock(Refund.class);
        when(refund.getStatus()).thenReturn("success");

        try (MockedStatic<Refund> iyzico = mockStatic(Refund.class)) {
            iyzico.when(() -> Refund.createV2(any(), any())).thenReturn(refund);

            service.cancelByAdmin(42L, "127.0.0.1");

            iyzico.verify(() -> Refund.createV2(any(), any()));
        }

        assertEquals("REFUNDED", payment.getStatus());
        assertEquals("İptal edildi", order.getStatus());
        assertEquals(5, product.getStock());
    }

    @Test
    void failedRefundChangesNothing() {
        Payment payment = payment("PAID");
        Refund refund = mock(Refund.class);
        when(refund.getStatus()).thenReturn("failure");
        when(refund.getErrorMessage()).thenReturn("İade süresi geçti");

        try (MockedStatic<Refund> iyzico = mockStatic(Refund.class)) {
            iyzico.when(() -> Refund.createV2(any(), any())).thenReturn(refund);

            BadRequestException error = assertThrows(BadRequestException.class,
                    () -> service.cancelByAdmin(42L, "127.0.0.1"));
            assertEquals("İade yapılamadı: İade süresi geçti", error.getMessage());
        }

        assertEquals("PAID", payment.getStatus());
        assertEquals("Hazırlanıyor", order.getStatus());
        assertEquals(3, product.getStock());
    }

    @Test
    void pendingPaymentIsClosedWithoutRefund() {
        order.setStatus("Ödeme bekliyor");
        Payment payment = payment("PENDING");

        try (MockedStatic<Refund> iyzico = mockStatic(Refund.class)) {
            service.cancelByAdmin(42L, "127.0.0.1");

            iyzico.verifyNoInteractions();
        }

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

        org.mockito.Mockito.verify(payments, never()).findByOrderIdForUpdate(any());
        assertEquals(3, product.getStock());
    }

    @Test
    void paidOrderWithoutIyzicoPaymentIdCannotBeRefunded() {
        Payment payment = payment("PAID");
        payment.setToken(null);

        assertThrows(BadRequestException.class, () -> service.cancelByAdmin(42L, "127.0.0.1"));

        assertEquals("PAID", payment.getStatus());
        assertEquals(3, product.getStock());
    }

    private Payment payment(String status) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setStatus(status);
        payment.setToken("iyzico-payment-1");
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        return payment;
    }
}
