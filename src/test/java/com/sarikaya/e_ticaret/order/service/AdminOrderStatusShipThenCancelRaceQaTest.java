package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.IntegrationTest;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.client.IyzicoServiceClient;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * QA 2. tur: önce "Kargoya verildi" (işlemi henüz commit edilmemiş, kilitleri tutuyor), aynı anda
 * "İptal". İptal, kargo işlemi bitene kadar beklemeli ve bitince SHIPPED'ı görüp reddedilmeli:
 * iade ve stok iadesi çağrılmamalı. Gerçek veritabanı, iki iş parçacığı. Ödeme kaydı olan ve olmayan
 * (eski) sipariş; ikincisinde ödeme kilidi boş geçer, yalnız sipariş kilidi sıralar (deadlock beklenmez).
 */
@IntegrationTest
class AdminOrderStatusShipThenCancelRaceQaTest {
    @Autowired private AdminOrderStatusService service;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private IyzicoServiceClient iyzicoService;

    private User user;
    private Order order;
    private Payment payment;

    @AfterEach
    void tearDown() {
        if (payment != null) {
            payments.deleteById(payment.getId());
        }
        orders.deleteById(order.getId());
        users.deleteById(user.getId());
    }

    private void givenOrder(boolean withPaidPayment) {
        user = users.saveAndFlush(User.builder()
                .username("ship-cancel-" + UUID.randomUUID()).password("test").role(Role.USER).build());
        order = new Order();
        order.setUser(user);
        order.setFirstName("Test");
        order.setLastName("Buyer");
        order.setPhone("05551234567");
        order.setCity("Istanbul");
        order.setDistrict("Kadikoy");
        order.setNeighborhood("Moda");
        order.setAddress("Test adres");
        order.setTotal(new BigDecimal("10.00"));
        order.setStatus(OrderStatus.PREPARING);
        order = orders.saveAndFlush(order);
        if (withPaidPayment) {
            payment = new Payment();
            payment.setOrder(order);
            payment.setStatus(PaymentStatus.PAID);
            payment.setAttemptId(UUID.randomUUID());
            payment.setToken("iyz-" + UUID.randomUUID());
            payment = payments.saveAndFlush(payment);
        }
    }

    @Test
    void cancelWaitingBehindUncommittedShipIsRejectedAndNothingIsRefunded() throws Exception {
        givenOrder(true);
        raceShipThenCancel();
        verify(iyzicoService, never()).refund(any(), anyString());
        verify(iyzicoService, never()).refundLegacy(any(), any(), anyString(), any());
        assertEquals(PaymentStatus.PAID, payments.findById(payment.getId()).orElseThrow().getStatus());
    }

    @Test
    void legacyOrderWithoutPaymentRecordCancelWaitingBehindShipIsRejected() throws Exception {
        givenOrder(false);
        raceShipThenCancel();
    }

    private void raceShipThenCancel() throws Exception {
        CountDownLatch shippedButNotCommitted = new CountDownLatch(1);
        CountDownLatch commitShip = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var ship = executor.submit(() -> tx.executeWithoutResult(status -> {
                service.changeStatus(order.getId(), "SHIPPED", "1.2.3.4"); // aynı işleme katılır, kilitler tutulur
                shippedButNotCommitted.countDown();
                try {
                    assertTrue(commitShip.await(15, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(shippedButNotCommitted.await(15, TimeUnit.SECONDS));

            var cancel = executor.submit(() -> service.changeStatus(order.getId(), "CANCELLED", "1.2.3.4"));
            Thread.sleep(700);
            assertFalse(cancel.isDone(), "iptal, kargo işlemi commit edilene kadar kilitte beklemeli");

            commitShip.countDown();
            ship.get(15, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class, () -> cancel.get(15, TimeUnit.SECONDS));
            assertInstanceOf(BadRequestException.class, error.getCause(), String.valueOf(error.getCause()));
        }
        assertEquals(OrderStatus.SHIPPED, orders.findById(order.getId()).orElseThrow().getStatus());
    }
}
