package com.sarikaya.e_ticaret.payment.service;

import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.cart.entity.CartItem;
import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.exception.PaymentServiceUnavailableException;
import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.client.IyzicoServiceClient;
import com.sarikaya.e_ticaret.payment.dto.CheckoutRequest;
import com.sarikaya.e_ticaret.payment.dto.PayOrderRequest;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentServiceTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CartItemRepository cartItems = mock(CartItemRepository.class);
    private final IyzicoServiceClient iyzicoService = mock(IyzicoServiceClient.class);
    private final PaymentService service = new PaymentService(orders, payments, products, users, cartItems, iyzicoService);

    private static final String KEY = "6f1c2a8e-3b4d-4c5e-9f00-112233445566";
    private static final UUID ATTEMPT = UUID.fromString("0b6c1f7e-1111-4222-8333-944455566677");

    @Test
    void checkoutWithoutThreeDSChargesCardAndMarksOrderPaid() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        Product product = product(5, "12.50");
        CartItem cartItem = mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        mockOrderSave();
        when(iyzicoService.startPayment(eq("u7-" + KEY), any(), anyBoolean())).thenReturn(result("SUCCEEDED"));

        var response = service.initialize("buyer", request(2, false), "127.0.0.1", KEY);

        assertEquals("success", response.result());
        assertNull(response.threeDSHtmlContent());
        assertEquals(3, product.getStock());
        verify(payments).save(argThat(payment -> PaymentStatus.PAID == payment.getStatus()
                && "iyzico-payment-1".equals(payment.getToken()) && ATTEMPT.equals(payment.getAttemptId())));
        verify(cartItems).deleteAll(List.of(cartItem));
    }

    @Test
    void cardGoesToIyzicoServiceWithBasketMatchingOrderTotal() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        Product product = product(5, "12.50");
        mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        mockOrderSave();
        when(iyzicoService.startPayment(any(), any(), anyBoolean())).thenReturn(result("SUCCEEDED"));

        service.initialize("buyer", request(2, false), "127.0.0.1", KEY);

        verify(iyzicoService).startPayment(eq("u7-" + KEY), argThat(body ->
                body.orderId() == 42L
                        && body.amount().compareTo(new BigDecimal("25.00")) == 0
                        && body.items().getFirst().price().compareTo(new BigDecimal("25.00")) == 0
                        && "5528790000000008".equals(body.card().number())
                        && !body.threeDS()), eq(true));
    }

    @Test
    void checkoutFitsOtherCartsToRemainingStock() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        Product product = product(5, "12.50");
        mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        mockOrderSave();
        when(iyzicoService.startPayment(any(), any(), anyBoolean())).thenReturn(result("SUCCEEDED"));

        service.initialize("buyer", request(2, false), "127.0.0.1", KEY);

        // Kalan stok 3: başkalarının sepetindeki fazla adet 3'e iner; alıcının sepetine dokunulmaz.
        verify(cartItems).fitToStock(4L, 3, 7L);
    }

    @Test
    void declinedCardThrowsSoOrderAndStockReservationRollBack() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        Product product = product(5, "12.50");
        mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        mockOrderSave();
        when(iyzicoService.startPayment(any(), any(), anyBoolean())).thenReturn(new IyzicoServiceClient.AttemptResult(
                ATTEMPT, 42L, "FAILED", null, "Kart limiti yetersiz", "t", null));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.initialize("buyer", request(2, false), "127.0.0.1", KEY));
        assertEquals("Ödeme alınamadı: Kart limiti yetersiz", error.getMessage());
        verify(cartItems, never()).deleteAll(any());
    }

    @Test
    void uncertainPaymentPutsOrderUnderReview() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        Product product = product(5, "12.50");
        mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        mockOrderSave();
        when(iyzicoService.startPayment(any(), any(), anyBoolean())).thenReturn(result("VERIFYING"));

        var response = service.initialize("buyer", request(2, false), "127.0.0.1", KEY);

        assertEquals("review", response.result());
        verify(payments).save(argThat(payment -> PaymentStatus.REVIEW == payment.getStatus()));
    }

    @Test
    void checkoutWithThreeDSReturnsBankPageAndKeepsPaymentPending() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        Product product = product(5, "12.50");
        mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        mockOrderSave();
        when(iyzicoService.startPayment(any(), any(), anyBoolean())).thenReturn(new IyzicoServiceClient.AttemptResult(
                ATTEMPT, 42L, "AWAITING_3DS", "iyzico-payment-1", null, "t", "<html>bank</html>"));

        var response = service.initialize("buyer", request(2, true), "127.0.0.1", KEY);

        assertEquals("threeds", response.result());
        assertEquals("<html>bank</html>", response.threeDSHtmlContent());
        assertEquals(3, product.getStock());
        verify(payments).save(argThat(payment -> PaymentStatus.PENDING == payment.getStatus()
                && "iyzico-payment-1".equals(payment.getToken()) && ATTEMPT.equals(payment.getAttemptId())));
    }

    @Test
    void invalidIdempotencyKeyIsRejectedBeforeAnything() {
        assertThrows(BadRequestException.class,
                () -> service.initialize("buyer", request(1, false), "127.0.0.1", "kisa"));
        verifyNoInteractions(users, products, orders, payments, iyzicoService);
    }

    @Test
    void threeDSCallbackCompletesPayment() {
        Payment payment = pendingPayment();
        Order order = payment.getOrder();
        when(payments.findByAttemptIdForUpdate(ATTEMPT)).thenReturn(Optional.of(payment));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(order.getUser()));
        CartItem cartItem = new CartItem();
        when(cartItems.findByUser_IdOrderByIdAsc(7L)).thenReturn(List.of(cartItem));
        when(iyzicoService.completeThreeDS(eq(ATTEMPT), any())).thenReturn(result("SUCCEEDED"));

        String outcome = service.completeThreeDS(callback("success", "1"));

        assertEquals("success", outcome);
        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertEquals(OrderStatus.PREPARING, order.getStatus());
        verify(cartItems).deleteAll(List.of(cartItem));

        // İkinci dönüş IyzicoService'e hiç gitmez.
        assertEquals("success", service.completeThreeDS(callback("success", "1")));
        verify(iyzicoService, times(1)).completeThreeDS(any(), any());
    }

    @Test
    void threeDSCallbackWithUncertainResultMovesOrderToReview() {
        Payment payment = pendingPayment();
        when(payments.findByAttemptIdForUpdate(ATTEMPT)).thenReturn(Optional.of(payment));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(payment.getOrder().getUser()));
        when(iyzicoService.completeThreeDS(eq(ATTEMPT), any())).thenReturn(result("VERIFYING"));

        assertEquals("review", service.completeThreeDS(callback("success", "1")));
        assertEquals(PaymentStatus.REVIEW, payment.getStatus());
        verifyNoInteractions(products);
    }

    @Test
    void failedBankVerificationLeavesOrderPendingWithoutCharging() {
        Payment payment = pendingPayment();
        when(payments.findByAttemptIdForUpdate(ATTEMPT)).thenReturn(Optional.of(payment));
        when(iyzicoService.completeThreeDS(eq(ATTEMPT), any())).thenReturn(result("FAILED"));

        assertEquals("declined", service.completeThreeDS(callback("failure", "0")));
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        verifyNoInteractions(products);
    }

    @Test
    void callbackForExpiredOrderIsNotForwarded() {
        Payment payment = pendingPayment();
        payment.setStatus(PaymentStatus.EXPIRED);
        when(payments.findByAttemptIdForUpdate(ATTEMPT)).thenReturn(Optional.of(payment));

        assertEquals("failed", service.completeThreeDS(callback("success", "1")));
        verifyNoInteractions(iyzicoService);
    }

    @Test
    void callbackWithMalformedConversationIdIsRejected() {
        assertThrows(BadRequestException.class, () -> service.completeThreeDS(
                new IyzicoServiceClient.ThreeDSCallback("success", "iyzico-payment-1", "data", "42", "1", null)));
        verifyNoInteractions(iyzicoService);
    }

    @Test
    void payExistingOrderChargesPendingOrder() {
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(payment.getOrder().getUser()));
        when(iyzicoService.startPayment(eq("u7-" + KEY), any(), anyBoolean())).thenReturn(result("SUCCEEDED"));

        var response = service.payExistingOrder("buyer", 42L,
                new PayOrderRequest("buyer@example.com", "12345678901", false, card()), "127.0.0.1", KEY);

        assertEquals("success", response.result());
        assertEquals(PaymentStatus.PAID, payment.getStatus());
    }

    @Test
    void cancelPendingOrderReleasesStock() {
        Payment payment = pendingPayment();
        Product product = product(2, "12.50");
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        when(iyzicoService.getAttempt(ATTEMPT)).thenReturn(result("AWAITING_3DS"));

        service.cancelPendingOrder("buyer", 42L);

        assertEquals(4, product.getStock());
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        assertEquals(OrderStatus.CANCELLED, payment.getOrder().getStatus());
    }

    @Test
    void cannotCancelWhileChargeIsInFlight() {
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        when(iyzicoService.getAttempt(ATTEMPT)).thenReturn(result("SENT"));

        BadRequestException error = assertThrows(BadRequestException.class, () -> service.cancelPendingOrder("buyer", 42L));
        assertTrue(error.getMessage().contains("işleniyor"));
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        verifyNoInteractions(products);
    }

    @Test
    void cannotCancelWhenPaymentServiceIsUnreachable() {
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        when(iyzicoService.getAttempt(ATTEMPT)).thenThrow(new RuntimeException("kapalı"));

        assertThrows(PaymentServiceUnavailableException.class, () -> service.cancelPendingOrder("buyer", 42L));
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
    }

    @Test
    void cannotCancelSomeoneElsesOrder() {
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));

        assertThrows(ResourceNotFoundException.class, () -> service.cancelPendingOrder("intruder", 42L));
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        verifyNoInteractions(products);
    }

    @Test
    void initializeRejectsInsufficientStockWithoutCallingIyzico() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        Product product = product(1, "12.50");
        mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.initialize("buyer", request(2, false), "127.0.0.1", KEY));
        assertEquals("Armut için yeterli stok yok (kalan: 1).", error.getMessage());
        verifyNoInteractions(iyzicoService);
        assertEquals(1, product.getStock());
        verifyNoInteractions(orders);
    }

    @Test
    void initializeRejectsAnotherPendingPaymentBeforeContactingProvider() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        when(payments.existsByOrder_User_IdAndStatusIn(7L, List.of(PaymentStatus.PENDING))).thenReturn(true);
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.initialize("buyer", request(1, false), "127.0.0.1", KEY));
        assertTrue(error.getMessage().contains("devam eden bir ödeme"));
        verifyNoInteractions(iyzicoService);
        verifyNoInteractions(orders, products, cartItems);
    }

    @Test
    void initializeRejectsStaleCartBeforeContactingProvider() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        when(cartItems.findByUser_IdOrderByIdAsc(7L)).thenReturn(List.of());
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.initialize("buyer", request(1, false), "127.0.0.1", KEY));
        assertTrue(error.getMessage().contains("Sepetiniz değişti"));
        verifyNoInteractions(iyzicoService);
        verifyNoInteractions(orders, products);
    }

    @Test
    void expireReservationsReleasesStockAndMarksOrderExpired() {
        Payment payment = pendingPayment();
        Order order = payment.getOrder();
        Product product = product(2, "12.50");
        when(payments.findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                eq(PaymentStatus.PENDING), any())).thenReturn(List.of(payment));
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        when(iyzicoService.getAttempt(ATTEMPT)).thenReturn(result("FAILED"));

        assertEquals(1, service.expireReservations());
        assertEquals(4, product.getStock());
        assertEquals(PaymentStatus.EXPIRED, payment.getStatus());
        assertEquals(OrderStatus.PAYMENT_EXPIRED, order.getStatus());
    }

    @Test
    void twoDCheckoutIsAcceptedAndProcessedInBackground() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        Product product = product(5, "12.50");
        mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        mockOrderSave();
        when(iyzicoService.startPayment(any(), any(), eq(true))).thenReturn(result("CREATED"));

        var response = service.initialize("buyer", request(2, false), "127.0.0.1", KEY);

        assertEquals("processing", response.result());
        assertEquals(42L, response.orderId());
        // Sipariş beklemede kalır; sepet henüz silinmez (ödeme reddedilirse kullanıcı tekrar dener).
        verify(payments).save(argThat(payment -> PaymentStatus.PENDING == payment.getStatus() && ATTEMPT.equals(payment.getAttemptId())));
        verify(cartItems, never()).deleteAll(any());
    }

    @Test
    void threeDSCheckoutStaysSynchronous() {
        User user = buyer();
        when(users.findByUsernameForUpdate("buyer")).thenReturn(Optional.of(user));
        Product product = product(5, "12.50");
        mockCart(user, product, 2);
        when(products.findByIdForUpdate(4L)).thenReturn(Optional.of(product));
        mockOrderSave();
        when(iyzicoService.startPayment(any(), any(), eq(false))).thenReturn(new IyzicoServiceClient.AttemptResult(
                ATTEMPT, 42L, "AWAITING_3DS", "iyzico-payment-1", null, "t", "<html>bank</html>"));

        assertEquals("threeds", service.initialize("buyer", request(2, true), "127.0.0.1", KEY).result());
    }

    @Test
    void statusReportsDeclineWithReasonAndKeepsOrderPending() {
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        when(iyzicoService.getAttempt(ATTEMPT)).thenReturn(new IyzicoServiceClient.AttemptResult(
                ATTEMPT, 42L, "FAILED", null, "Kart limiti yetersiz", "t", null));

        var status = service.paymentStatus("buyer", 42L);

        assertEquals("declined", status.result());
        assertEquals("Kart limiti yetersiz", status.message());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
    }

    @Test
    void statusCompletesOrderWhenEventIsLate() {
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(payment.getOrder().getUser()));
        when(iyzicoService.getAttempt(ATTEMPT)).thenReturn(result("SUCCEEDED"));

        assertEquals("success", service.paymentStatus("buyer", 42L).result());
        assertEquals(PaymentStatus.PAID, payment.getStatus());
    }

    @Test
    void statusWhileProviderIsWorkingIsProcessing() {
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        when(iyzicoService.getAttempt(ATTEMPT)).thenReturn(result("SENT"));

        assertEquals("processing", service.paymentStatus("buyer", 42L).result());
    }

    @Test
    void statusOfSomeoneElsesOrderIsNotFound() {
        Payment payment = pendingPayment();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));

        assertThrows(ResourceNotFoundException.class, () -> service.paymentStatus("intruder", 42L));
        verifyNoInteractions(iyzicoService);
    }

    @Test
    void expiryWaitsWhileChargeIsInFlightOrServiceIsUnreachable() {
        Payment inFlight = pendingPayment();
        when(payments.findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(eq(PaymentStatus.PENDING), any()))
                .thenReturn(List.of(inFlight));
        when(iyzicoService.getAttempt(ATTEMPT)).thenReturn(result("SENT"));

        assertEquals(0, service.expireReservations());
        assertEquals(PaymentStatus.PENDING, inFlight.getStatus());

        when(iyzicoService.getAttempt(ATTEMPT)).thenThrow(new RuntimeException("servis kapalı"));
        assertEquals(0, service.expireReservations());
        assertEquals(PaymentStatus.PENDING, inFlight.getStatus());
        verifyNoInteractions(products);
    }

    @Test
    void expiryCompletesOrderInsteadWhenMoneyWasTaken() {
        Payment payment = pendingPayment();
        when(payments.findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(eq(PaymentStatus.PENDING), any()))
                .thenReturn(List.of(payment));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(payment.getOrder().getUser()));
        when(iyzicoService.getAttempt(ATTEMPT)).thenReturn(result("SUCCEEDED"));

        assertEquals(0, service.expireReservations());
        assertEquals(PaymentStatus.PAID, payment.getStatus());
        verifyNoInteractions(products);
    }

    private static IyzicoServiceClient.AttemptResult result(String status) {
        return new IyzicoServiceClient.AttemptResult(ATTEMPT, 42L, status, "iyzico-payment-1", null, "t", null);
    }

    private static IyzicoServiceClient.ThreeDSCallback callback(String status, String mdStatus) {
        return new IyzicoServiceClient.ThreeDSCallback(status, "iyzico-payment-1", "data",
                ATTEMPT.toString(), mdStatus, "imza");
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
        order.setStatus(OrderStatus.PAYMENT_PENDING);
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
        payment.setAttemptId(ATTEMPT);
        payment.setStatus(PaymentStatus.PENDING);
        return payment;
    }
}
