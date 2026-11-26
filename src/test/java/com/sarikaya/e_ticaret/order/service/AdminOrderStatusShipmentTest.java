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
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * S6 / K-A7: kargoda (FAILED dışı gönderisi olan) siparişin durumu elle değiştirilemez; aynı duruma
 * "değişiklik" yok sayılır. Gönderi FAILED ise (koli hiç çıkmadı) iptal serbest, SHIPPED/DELIVERED yasak.
 */
@IntegrationTest
class AdminOrderStatusShipmentTest {
    @Autowired private AdminOrderStatusService service;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderShipmentRepository shipments;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private IyzicoServiceClient iyzicoService;

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
    void activeShipmentBlocksEveryManualChange() {
        for (ShipmentStatus active : new ShipmentStatus[]{ShipmentStatus.REQUESTED, ShipmentStatus.CREATED,
                ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERY_FAILED, ShipmentStatus.DELIVERED}) {
            OrderStatus orderStatus = active == ShipmentStatus.REQUESTED ? OrderStatus.PREPARING
                    : active == ShipmentStatus.DELIVERED ? OrderStatus.DELIVERED : OrderStatus.SHIPPED;
            Order order = paidOrder(orderStatus);
            data.shipment(order, active, 1);

            for (String target : new String[]{"CANCELLED", "SHIPPED", "DELIVERED", "PREPARING"}) {
                if (target.equals(orderStatus.name())) {
                    continue;
                }
                ConflictException error = assertThrows(ConflictException.class,
                        () -> service.changeStatus(order.getId(), target, "1.2.3.4"), active + " → " + target);
                assertEquals("ORDER_HAS_SHIPMENT", error.getCode());
            }
            assertEquals(orderStatus, orders.findById(order.getId()).orElseThrow().getStatus());
        }
        verify(iyzicoService, never()).refund(any(), anyString());
    }

    @Test
    void sameStatusIsANoOpEvenWithShipment() {
        Order order = paidOrder(OrderStatus.PREPARING);
        data.shipment(order, ShipmentStatus.REQUESTED, 1);

        assertEquals(OrderStatus.PREPARING, service.changeStatus(order.getId(), "PREPARING", "1.2.3.4").status());
    }

    @Test
    void failedShipmentAllowsCancelButNotManualShipping() {
        Order order = paidOrder(OrderStatus.PREPARING);
        data.shipment(order, ShipmentStatus.FAILED, 2);

        assertEquals("ORDER_HAS_SHIPMENT", assertThrows(ConflictException.class,
                () -> service.changeStatus(order.getId(), "SHIPPED", "1.2.3.4")).getCode());
        assertEquals("ORDER_HAS_SHIPMENT", assertThrows(ConflictException.class,
                () -> service.changeStatus(order.getId(), "DELIVERED", "1.2.3.4")).getCode());

        assertEquals(OrderStatus.CANCELLED, service.changeStatus(order.getId(), "CANCELLED", "1.2.3.4").status());
        assertEquals(PaymentStatus.REFUNDED, payments.findByOrder_Id(order.getId()).orElseThrow().getStatus());
    }

    @Test
    void orderWithoutShipmentKeepsCurrentBehaviour() {
        Order order = paidOrder(OrderStatus.PREPARING);

        assertEquals(OrderStatus.SHIPPED, service.changeStatus(order.getId(), "SHIPPED", "1.2.3.4").status());
    }

    private Order paidOrder(OrderStatus status) {
        Order order = data.order(data.user(Role.USER), status, false);
        data.payment(order, PaymentStatus.PAID);
        return order;
    }
}
