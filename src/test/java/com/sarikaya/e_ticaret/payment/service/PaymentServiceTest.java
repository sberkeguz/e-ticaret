package com.sarikaya.e_ticaret.payment.service;

import com.iyzipay.model.ThreedsInitialize;
import com.iyzipay.model.ThreedsPayment;
import com.sarikaya.e_ticaret.cart.entity.CartItem;
import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.dto.CheckoutRequest;
import com.sarikaya.e_ticaret.payment.dto.PayOrderRequest;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentServiceTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CartItemRepository cartItems = mock(CartItemRepository.class);

    @Test
    void checkoutWithoutThreeDSChargesCardAndMarksOrderPaid() {
        PaymentService service = service("api-key", "secret");
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        Product product = product(5, "12.50");
        CartItem cartItem = mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        mockOrderSave();

        com.iyzipay.model.Payment result = successfulPayment(com.iyzipay.model.Payment.class);
        when(result.verifySignature("secret")).thenReturn(true);

        try (MockedStatic<com.iyzipay.model.Payment> iyzico = mockStatic(com.iyzipay.model.Payment.class)) {
            iyzico.when(() -> com.iyzipay.model.Payment.create(any(), any())).thenReturn(result);

            var response = service.initialize("buyer", request(2, false), "127.0.0.1");

            assertEquals("success", response.result());
            assertNull(response.threeDSHtmlContent());
            assertEquals(3, product.getStock());
            verify(payments).save(argThat(payment ->
                    "PAID".equals(payment.getStatus()) && "iyzico-payment-1".equals(payment.getToken())));
            verify(cartItems).deleteAll(List.of(cartItem));
        }
    }

    @Test
    void declinedCardThrowsSoOrderAndStockReservationRollBack() {
        PaymentService service = service("api-key", "secret");
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        Product product = product(5, "12.50");
        mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        mockOrderSave();

        com.iyzipay.model.Payment result = mock(com.iyzipay.model.Payment.class);
        when(result.getStatus()).thenReturn("failure");
        when(result.getErrorMessage()).thenReturn("Kart limiti yetersiz");

        try (MockedStatic<com.iyzipay.model.Payment> iyzico = mockStatic(com.iyzipay.model.Payment.class)) {
            iyzico.when(() -> com.iyzipay.model.Payment.create(any(), any())).thenReturn(result);

            BadRequestException error = assertThrows(BadRequestException.class,
                    () -> service.initialize("buyer", request(2, false), "127.0.0.1"));
            assertEquals("Ödeme alınamadı: Kart limiti yetersiz", error.getMessage());
        }
        verify(cartItems, never()).deleteAll(any());
    }

    @Test
    void checkoutWithThreeDSReturnsBankPageAndKeepsPaymentPending() {
        PaymentService service = service("api-key", "secret");
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        Product product = product(5, "12.50");
        mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        mockOrderSave();

        ThreedsInitialize result = mock(ThreedsInitialize.class);
        when(result.getStatus()).thenReturn("success");
        when(result.getPaymentId()).thenReturn("iyzico-payment-1");
        when(result.getHtmlContent()).thenReturn(Base64.getEncoder()
                .encodeToString("<html>bank</html>".getBytes(StandardCharsets.UTF_8)));

        try (MockedStatic<ThreedsInitialize> iyzico = mockStatic(ThreedsInitialize.class)) {
            iyzico.when(() -> ThreedsInitialize.create(any(), any())).thenReturn(result);

            var response = service.initialize("buyer", request(2, true), "127.0.0.1");

            assertEquals("threeds", response.result());
            assertEquals("<html>bank</html>", response.threeDSHtmlContent());
            assertEquals(3, product.getStock());
            verify(payments).save(argThat(payment ->
                    "PENDING".equals(payment.getStatus()) && "iyzico-payment-1".equals(payment.getToken())));
        }
    }

    @Test
    void threeDSCallbackCompletesSignedPayment() {
        PaymentService service = service("api-key", "secret");
        Payment payment = pendingPayment();
        Order order = payment.getOrder();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(order.getUser()));
        CartItem cartItem = new CartItem();
        when(cartItems.findByUser_IdOrderByIdAsc(7L)).thenReturn(List.of(cartItem));

        ThreedsPayment result = successfulPayment(ThreedsPayment.class);
        when(result.verifySignature("secret")).thenReturn(true);

        try (MockedStatic<ThreedsPayment> iyzico = mockStatic(ThreedsPayment.class)) {
            iyzico.when(() -> ThreedsPayment.create(any(), any())).thenReturn(result);

            String outcome = service.completeThreeDS("success", "iyzico-payment-1", "data", "42", "1");

            assertEquals("success", outcome);
            assertEquals("PAID", payment.getStatus());
            assertEquals("Hazırlanıyor", order.getStatus());
            verify(cartItems).deleteAll(List.of(cartItem));

            assertEquals("success",
                    service.completeThreeDS("success", "iyzico-payment-1", "data", "42", "1"));
            iyzico.verify(() -> ThreedsPayment.create(any(), any()), times(1));
        }
    }

    @Test
    void threeDSCallbackWithUnverifiedSignatureMovesOrderToReview() {
        PaymentService service = service("api-key", "secret");
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(payment.getOrder().getUser()));

        ThreedsPayment result = successfulPayment(ThreedsPayment.class);
        when(result.verifySignature("secret")).thenReturn(false);

        try (MockedStatic<ThreedsPayment> iyzico = mockStatic(ThreedsPayment.class)) {
            iyzico.when(() -> ThreedsPayment.create(any(), any())).thenReturn(result);

            String outcome = service.completeThreeDS("success", "iyzico-payment-1", "data", "42", "1");

            assertEquals("review", outcome);
            assertEquals("REVIEW", payment.getStatus());
        }
        verifyNoInteractions(products);
    }

    @Test
    void failedBankVerificationLeavesOrderPendingWithoutCharging() {
        PaymentService service = service("api-key", "secret");
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));

        try (MockedStatic<ThreedsPayment> iyzico = mockStatic(ThreedsPayment.class)) {
            String outcome = service.completeThreeDS("failure", "iyzico-payment-1", "data", "42", "0");

            assertEquals("declined", outcome);
            iyzico.verifyNoInteractions();
        }
        assertEquals("PENDING", payment.getStatus());
        verifyNoInteractions(products);
    }

    @Test
    void callbackWithForeignPaymentIdIsRejected() {
        PaymentService service = service("api-key", "secret");
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));

        assertThrows(BadRequestException.class,
                () -> service.completeThreeDS("success", "someone-else", "data", "42", "1"));
        assertEquals("PENDING", payment.getStatus());
    }

    @Test
    void payExistingOrderChargesPendingOrder() {
        PaymentService service = service("api-key", "secret");
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(payment.getOrder().getUser()));

        com.iyzipay.model.Payment result = successfulPayment(com.iyzipay.model.Payment.class);
        when(result.verifySignature("secret")).thenReturn(true);

        try (MockedStatic<com.iyzipay.model.Payment> iyzico = mockStatic(com.iyzipay.model.Payment.class)) {
            iyzico.when(() -> com.iyzipay.model.Payment.create(any(), any())).thenReturn(result);

            var response = service.payExistingOrder("buyer", 42L,
                    new PayOrderRequest("buyer@example.com", "12345678901", false, card()), "127.0.0.1");

            assertEquals("success", response.result());
            assertEquals("PAID", payment.getStatus());
        }
    }

    @Test
    void cancelPendingOrderReleasesStock() {
        PaymentService service = service("api-key", "secret");
        Payment payment = pendingPayment();
        Product product = product(2, "12.50");
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));

        service.cancelPendingOrder("buyer", 42L);

        assertEquals(4, product.getStock());
        assertEquals("CANCELLED", payment.getStatus());
        assertEquals("İptal edildi", payment.getOrder().getStatus());
    }

    @Test
    void cannotCancelSomeoneElsesOrder() {
        PaymentService service = service("api-key", "secret");
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));

        assertThrows(ResourceNotFoundException.class, () -> service.cancelPendingOrder("intruder", 42L));
        assertEquals("PENDING", payment.getStatus());
        verifyNoInteractions(products);
    }

    @Test
    void initializeRejectsInsufficientStockWithoutCallingIyzico() {
        PaymentService service = service("api-key", "secret");
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        Product product = product(1, "12.50");
        mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));

        try (MockedStatic<com.iyzipay.model.Payment> iyzico = mockStatic(com.iyzipay.model.Payment.class)) {
            BadRequestException error = assertThrows(BadRequestException.class,
                    () -> service.initialize("buyer", request(2, false), "127.0.0.1"));
            assertEquals("Armut için yeterli stok yok (kalan: 1).", error.getMessage());
            iyzico.verifyNoInteractions();
        }
        assertEquals(1, product.getStock());
        verifyNoInteractions(orders);
    }

    @Test
    void initializeRequiresIyzicoCredentials() {
        PaymentService service = service("", "");

        assertThrows(BadRequestException.class,
                () -> service.initialize("buyer", request(1, false), "127.0.0.1"));
        verifyNoInteractions(users, products, orders, payments);
    }

    @Test
    void initializeRejectsAnotherPendingPaymentBeforeContactingProvider() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        when(payments.existsByOrder_User_IdAndStatusIn(7L, List.of("PENDING"))).thenReturn(true);
        try (MockedStatic<com.iyzipay.model.Payment> iyzico = mockStatic(com.iyzipay.model.Payment.class)) {
            BadRequestException error = assertThrows(BadRequestException.class,
                    () -> service("api-key", "secret").initialize("buyer", request(1, false), "127.0.0.1"));
            assertTrue(error.getMessage().contains("devam eden bir ödeme"));
            iyzico.verifyNoInteractions();
        }
        verifyNoInteractions(orders, products, cartItems);
    }

    @Test
    void initializeRejectsStaleCartBeforeContactingProvider() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        when(cartItems.findByUser_IdOrderByIdAsc(7L)).thenReturn(List.of());
        try (MockedStatic<com.iyzipay.model.Payment> iyzico = mockStatic(com.iyzipay.model.Payment.class)) {
            BadRequestException error = assertThrows(BadRequestException.class,
                    () -> service("api-key", "secret").initialize("buyer", request(1, false), "127.0.0.1"));
            assertTrue(error.getMessage().contains("Sepetiniz değişti"));
            iyzico.verifyNoInteractions();
        }
        verifyNoInteractions(orders, products);
    }

    @Test
    void expireReservationsReleasesStockAndMarksOrderExpired() {
        PaymentService service = service("api-key", "secret");
        Payment payment = pendingPayment();
        Order order = payment.getOrder();
        Product product = product(2, "12.50");
        when(payments.findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                eq("PENDING"), any())).thenReturn(List.of(payment));
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));

        assertEquals(1, service.expireReservations());
        assertEquals(4, product.getStock());
        assertEquals("EXPIRED", payment.getStatus());
        assertEquals("Ödeme süresi doldu", order.getStatus());
    }

    private PaymentService service(String apiKey, String secret) {
        return new PaymentService(
                orders, payments, products, users, cartItems, apiKey, secret,
                "https://sandbox-api.iyzipay.com",
                "https://example.test/api/payments/callback",
                "https://example.test");
    }

    private static <T extends com.iyzipay.model.PaymentResource> T successfulPayment(Class<T> type) {
        T result = mock(type);
        when(result.getStatus()).thenReturn("success");
        when(result.getPaymentId()).thenReturn("iyzico-payment-1");
        when(result.getBasketId()).thenReturn("42");
        when(result.getConversationId()).thenReturn("42");
        when(result.getCurrency()).thenReturn("TRY");
        when(result.getPrice()).thenReturn(new BigDecimal("25.00"));
        when(result.getPaidPrice()).thenReturn(new BigDecimal("25.00"));
        // Gerçek Ödeme API yanıtı gibi: paymentStatus alanı yok.
        when(result.getPaymentStatus()).thenReturn(null);
        when(result.getFraudStatus()).thenReturn(1);
        return result;
    }

    private void mockOrderSave() {
        when(orders.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(42L);
            return order;
        });
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(payments.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CartItem mockCart(User user, Product product, int quantity) {
        CartItem item = new CartItem();
        item.setProduct(product);
        item.setQuantity(quantity);
        when(cartItems.findByUser_IdOrderByIdAsc(user.getId())).thenReturn(List.of(item));
        return item;
    }

    private static User buyer() {
        return User.builder().id(7L).username("buyer").build();
    }

    private static Product product(int stock, String price) {
        Product product = new Product();
        product.setId(4L);
        product.setName("Armut");
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setActive(true);
        return product;
    }

    private static CheckoutRequest.Card card() {
        return new CheckoutRequest.Card("Test Buyer", "5528790000000008", "12", "2030", "123");
    }

    private static CheckoutRequest request(int quantity, boolean threeDS) {
        return new CheckoutRequest(
                "Test", "Buyer", "05551234567", "buyer@example.com", "12345678901",
                "Istanbul", "Kadikoy", "Moda", "Test adres",
                List.of(new com.sarikaya.e_ticaret.order.dto.OrderRequest.Item(4L, quantity)),
                threeDS, card());
    }

    private static Payment pendingPayment() {
        Order order = new Order();
        User user = buyer();
        order.setUser(user);
        order.setId(42L);
        order.setFirstName("Test");
        order.setLastName("Buyer");
        order.setPhone("05551234567");
        order.setCity("Istanbul");
        order.setAddress("Test adres");
        order.setTotal(new BigDecimal("25.00"));
        order.setStatus("Ödeme bekliyor");
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductId(4L);
        item.setProductName("Armut");
        item.setPrice(new BigDecimal("12.50"));
        item.setQuantity(2);
        order.getItems().add(item);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setToken("iyzico-payment-1");
        payment.setStatus("PENDING");
        return payment;
    }
}
