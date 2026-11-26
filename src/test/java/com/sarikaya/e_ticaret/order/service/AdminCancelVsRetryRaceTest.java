package com.sarikaya.e_ticaret.order.service;

import com.sarikaya.e_ticaret.IntegrationTest;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.client.IyzicoServiceClient;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.shipment.ShipmentTestData;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.exception.ConflictException;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import com.sarikaya.e_ticaret.shipment.service.ShipmentService;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Callable;
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
 * FAILED gönderili siparişte aynı anda "Tekrar dene" ve "İptal": hangisi önce kilidi alırsa o geçer,
 * diğeri onun sonucunu görüp 409 alır. Sonuç asla "sipariş iptal + kargo yeniden istendi" olmaz.
 */
@IntegrationTest
class AdminCancelVsRetryRaceTest {
    @Autowired private AdminOrderStatusService statusService;
    @Autowired private ShipmentService shipmentService;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderShipmentRepository shipments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private IyzicoServiceClient iyzicoService;

    private ShipmentTestData data;
    private Order order;

    @BeforeEach
    void setUp() {
        data = new ShipmentTestData(users, orders, payments, shipments, jdbc);
        order = data.order(data.user(Role.USER), OrderStatus.PREPARING);
        data.payment(order, PaymentStatus.PAID);
        data.shipment(order, ShipmentStatus.FAILED, 1);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    @Test
    void cancelWaitingBehindRetryIsRejected() throws Exception {
        Throwable cancelError = firstHoldsLocks(
                () -> shipmentService.retry(order.getId(), 1),
                () -> statusService.changeStatus(order.getId(), "CANCELLED", "1.2.3.4"));

        assertEquals("ORDER_HAS_SHIPMENT", ((ConflictException) cancelError).getCode());
        assertEquals(OrderStatus.PREPARING, orders.findById(order.getId()).orElseThrow().getStatus());
        assertEquals(ShipmentStatus.REQUESTED, shipments.findByOrderId(order.getId()).orElseThrow().getStatus());
        verify(iyzicoService, never()).refund(any(), anyString());
    }

    @Test
    void retryWaitingBehindCancelIsRejected() throws Exception {
        Throwable retryError = firstHoldsLocks(
                () -> statusService.changeStatus(order.getId(), "CANCELLED", "1.2.3.4"),
                () -> {
                    shipmentService.retry(order.getId(), 1);
                    return null;
                });

        assertEquals("SHIPMENT_NOT_RETRYABLE", ((ConflictException) retryError).getCode());
        assertEquals(OrderStatus.CANCELLED, orders.findById(order.getId()).orElseThrow().getStatus());
        assertEquals(ShipmentStatus.FAILED, shipments.findByOrderId(order.getId()).orElseThrow().getStatus());
        Integer commands = jdbc.queryForObject("SELECT count(*) FROM outbox_messages WHERE type = 'ShipmentRequested' "
                + "AND payload LIKE ?", Integer.class, "%\"orderId\":" + order.getId() + ",%");
        assertEquals(0, commands);
    }

    /** first işlemi kilitleri tutarken second başlar; second kilitte beklemeli, first commit olunca 409 almalı. */
    private Throwable firstHoldsLocks(Runnable first, Callable<?> second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> tx.executeWithoutResult(status -> {
                first.run();
                firstDone.countDown();
                try {
                    assertTrue(commitFirst.await(15, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(firstDone.await(15, TimeUnit.SECONDS));
            var secondFuture = executor.submit(second);
            Thread.sleep(700);
            assertFalse(secondFuture.isDone(), "ikinci işlem kilitte beklemeli");
            commitFirst.countDown();
            firstFuture.get(15, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class, () -> secondFuture.get(15, TimeUnit.SECONDS));
            assertInstanceOf(ConflictException.class, error.getCause(), String.valueOf(error.getCause()));
            return error.getCause();
        }
    }
}
