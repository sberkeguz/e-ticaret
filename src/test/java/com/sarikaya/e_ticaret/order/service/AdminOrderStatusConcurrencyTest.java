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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * İki admin aynı ödenmiş siparişe aynı anda farklı durum veriyor: biri iptal (para iadesi), biri
 * "Kargoya verildi". İkisi birden başarılı olamaz; aksi hâlde para iade edilmişken mal kargoya çıkar.
 * Gerçek veritabanıyla (TestContainers) ödeme → sipariş satır kilidi sırası sınanır.
 */
@IntegrationTest
class AdminOrderStatusConcurrencyTest {
    @Autowired private AdminOrderStatusService service;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @MockitoBean private IyzicoServiceClient iyzicoService;

    private User user;
    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        user = users.saveAndFlush(User.builder()
                .username("admin-race-" + UUID.randomUUID()).password("test").role(Role.USER).build());
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
        payment = new Payment();
        payment.setOrder(order);
        payment.setStatus(PaymentStatus.PAID);
        payment.setAttemptId(UUID.randomUUID());
        payment.setToken("iyz-" + UUID.randomUUID());
        payment = payments.saveAndFlush(payment);
    }

    @AfterEach
    void tearDown() {
        payments.deleteById(payment.getId());
        orders.deleteById(order.getId());
        users.deleteById(user.getId());
    }

    @Test
    void cancelAndShipAtTheSameTimeCannotBothSucceed() throws Exception {
        CountDownLatch refundStarted = new CountDownLatch(1);
        CountDownLatch shipFinished = new CountDownLatch(1);
        when(iyzicoService.refund(any(), anyString())).thenAnswer(invocation -> {
            refundStarted.countDown();
            // Kilit yoksa "kargoya ver" isteği bu arada biter; kilit varsa bekler ve süre dolar.
            shipFinished.await(2, TimeUnit.SECONDS);
            return null;
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancel = executor.submit(() -> service.changeStatus(order.getId(), "CANCELLED", "1.2.3.4"));
            assertTrue(refundStarted.await(10, TimeUnit.SECONDS));
            var ship = executor.submit(() -> {
                try {
                    return service.changeStatus(order.getId(), "SHIPPED", "1.2.3.4");
                } finally {
                    shipFinished.countDown();
                }
            });

            cancel.get(15, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class, () -> ship.get(15, TimeUnit.SECONDS),
                    "iptal sürerken gelen 'kargoya ver' isteği, iptal bitince reddedilmeli");
            assertInstanceOf(BadRequestException.class, error.getCause());
        }

        verify(iyzicoService, times(1)).refund(any(), anyString());
        assertEquals(OrderStatus.CANCELLED, orders.findById(order.getId()).orElseThrow().getStatus());
        assertEquals(PaymentStatus.REFUNDED, payments.findById(payment.getId()).orElseThrow().getStatus());
    }
}
