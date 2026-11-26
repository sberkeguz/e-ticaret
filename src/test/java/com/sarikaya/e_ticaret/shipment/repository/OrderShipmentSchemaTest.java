package com.sarikaya.e_ticaret.shipment.repository;

import com.sarikaya.e_ticaret.IntegrationTest;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.shipment.entity.OrderShipment;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V21: order_shipments tablosu, kısıtları ve depo sorguları. Alıcı bilgisi bu tabloda tutulmaz. */
@IntegrationTest
class OrderShipmentSchemaTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private OrderShipmentRepository shipments;
    @Autowired private PlatformTransactionManager transactionManager;

    private User user;
    private Order order;
    private Order otherOrder;

    @BeforeEach
    void setUp() {
        user = users.saveAndFlush(User.builder()
                .username("shipment-schema-" + UUID.randomUUID()).password("test").role(Role.USER).build());
        order = orders.saveAndFlush(order());
        otherOrder = orders.saveAndFlush(order());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM order_shipments WHERE order_id IN (?, ?)", order.getId(), otherOrder.getId());
        orders.deleteById(order.getId());
        orders.deleteById(otherOrder.getId());
        users.deleteById(user.getId());
    }

    @Test
    void tableHasNoRecipientColumns() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'order_shipments'", String.class);

        assertTrue(columns.containsAll(List.of("id", "order_id", "request_no", "status", "cargo_shipment_id",
                "carrier_code", "carrier_name", "tracking_number", "failure_code", "status_at",
                "last_event_sequence", "created_at", "updated_at")), columns::toString);
        for (String forbidden : List.of("first_name", "last_name", "phone", "address", "city", "district",
                "neighborhood", "recipient", "failure_reason")) {
            assertTrue(!columns.contains(forbidden), forbidden + " sütunu olmamalı");
        }
    }

    @Test
    void oneShipmentPerOrder() {
        shipments.saveAndFlush(shipment(order, ShipmentStatus.REQUESTED, Instant.now()));

        assertThrows(DataIntegrityViolationException.class,
                () -> shipments.saveAndFlush(shipment(order, ShipmentStatus.REQUESTED, Instant.now())));
    }

    @Test
    void unknownStatusIsRejectedByDatabase() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO order_shipments (order_id, request_no, status, status_at, created_at, updated_at)
                VALUES (?, 1, 'SENDING', now(), now(), now())""", order.getId()));
    }

    @Test
    void lastEventSequenceDefaultsToZero() {
        jdbc.update("""
                INSERT INTO order_shipments (order_id, request_no, status, status_at, created_at, updated_at)
                VALUES (?, 1, 'REQUESTED', now(), now(), now())""", order.getId());

        assertEquals(0, shipments.findByOrderId(order.getId()).orElseThrow().getLastEventSequence());
    }

    @Test
    void repositoryQueries() {
        Instant old = Instant.now().minus(Duration.ofHours(1));
        shipments.saveAndFlush(shipment(order, ShipmentStatus.REQUESTED, old));
        OrderShipment created = shipment(otherOrder, ShipmentStatus.CREATED, old);
        created.setCargoShipmentId(UUID.randomUUID());
        created.setTrackingNumber("TRK-1");
        shipments.saveAndFlush(created);

        assertEquals(2, shipments.findAllByOrderIdIn(List.of(order.getId(), otherOrder.getId())).size());
        assertTrue(shipments.countByStatusAndStatusAtBefore(ShipmentStatus.REQUESTED, Instant.now().minus(Duration.ofMinutes(30))) >= 1);
        assertEquals("TRK-1", shipments.findByOrderId(otherOrder.getId()).orElseThrow().getTrackingNumber());

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertEquals(ShipmentStatus.REQUESTED,
                        shipments.findByOrderIdForUpdate(order.getId()).orElseThrow().getStatus()));
    }

    private Order order() {
        Order o = new Order();
        o.setUser(user);
        o.setFirstName("Test");
        o.setLastName("Buyer");
        o.setPhone("05551234567");
        o.setCity("Istanbul");
        o.setDistrict("Kadikoy");
        o.setNeighborhood("Moda");
        o.setAddress("Test adres");
        o.setTotal(new BigDecimal("10.00"));
        o.setStatus(OrderStatus.PREPARING);
        return o;
    }

    private static OrderShipment shipment(Order order, ShipmentStatus status, Instant statusAt) {
        OrderShipment shipment = new OrderShipment();
        shipment.setOrderId(order.getId());
        shipment.setRequestNo(1);
        shipment.setStatus(status);
        shipment.setStatusAt(statusAt);
        return shipment;
    }
}
