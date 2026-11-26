package com.sarikaya.e_ticaret.shipment.service;

import com.sarikaya.e_ticaret.IntegrationTest;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.shipment.ShipmentTestData;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.exception.ConflictException;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Eşzamanlı "Kargoya ver" / "Tekrar dene": biri geçer, diğeri 409; tek gönderi, tek komut. */
@IntegrationTest
class ShipmentServiceIntegrationTest {
    @Autowired private ShipmentService service;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderShipmentRepository shipments;
    @Autowired private JdbcTemplate jdbc;

    private ShipmentTestData data;

    @BeforeEach
    void setUp() {
        data = new ShipmentTestData(users, orders, payments, shipments, jdbc);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    @Test
    void concurrentShipRequestsCreateOneShipment() throws Exception {
        Order order = paidOrder();

        List<Throwable> outcomes = race(() -> {
            service.request(order.getId());
            return null;
        });

        assertEquals(1, outcomes.stream().filter(o -> o == null).count(), outcomes::toString);
        ConflictException conflict = (ConflictException) outcomes.stream().filter(o -> o != null).findFirst().orElseThrow();
        assertEquals("SHIPMENT_EXISTS", conflict.getCode());
        assertEquals(1, count("SELECT count(*) FROM order_shipments WHERE order_id = ?", order.getId()));
        assertEquals(1, commands(order));
    }

    @Test
    void concurrentRetriesRequestOnce() throws Exception {
        Order order = paidOrder();
        data.shipment(order, ShipmentStatus.FAILED, 1);

        List<Throwable> outcomes = race(() -> {
            service.retry(order.getId(), 1);
            return null;
        });

        assertEquals(1, outcomes.stream().filter(o -> o == null).count(), outcomes::toString);
        assertEquals("SHIPMENT_NOT_RETRYABLE",
                ((ConflictException) outcomes.stream().filter(o -> o != null).findFirst().orElseThrow()).getCode());
        assertEquals(2, shipments.findByOrderId(order.getId()).orElseThrow().getRequestNo());
        assertEquals(1, commands(order));
    }

    private List<Throwable> race(Supplier<Void> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return action.get();
                }));
            }
            start.countDown();
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<?> future : futures) {
                try {
                    future.get(20, TimeUnit.SECONDS);
                    outcomes.add(null);
                } catch (ExecutionException failed) {
                    assertInstanceOf(ConflictException.class, failed.getCause(), String.valueOf(failed.getCause()));
                    outcomes.add(failed.getCause());
                }
            }
            return outcomes;
        }
    }

    private Order paidOrder() {
        Order order = data.order(data.user(Role.USER), OrderStatus.PREPARING);
        data.payment(order, PaymentStatus.PAID);
        return order;
    }

    private int commands(Order order) {
        return count("SELECT count(*) FROM outbox_messages WHERE type = 'ShipmentRequested' AND payload LIKE ?",
                "%\"orderId\":" + order.getId() + ",%");
    }

    private int count(String sql, Object arg) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arg);
        return value == null ? 0 : value;
    }
}
