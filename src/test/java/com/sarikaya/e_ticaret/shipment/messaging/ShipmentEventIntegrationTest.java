package com.sarikaya.e_ticaret.shipment.messaging;

import com.sarikaya.e_ticaret.IntegrationTest;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.shipment.ShipmentTestData;
import com.sarikaya.e_ticaret.shipment.entity.OrderShipment;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Gerçek uygulama bağlamı (Spring'in JSON ayarları, işlem, satır kilitleri) ile cargo-service'in yazdığı
 * olay gövdesi: CREATED olayı hazırlanan siparişi SHIPPED yapar; aynı mesaj ikinci kez uygulanmaz.
 */
@IntegrationTest
class ShipmentEventIntegrationTest {
    @Autowired private ShipmentEventListener listener;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderShipmentRepository shipments;
    @Autowired private JdbcTemplate jdbc;

    private ShipmentTestData data;
    private final String messageId = UUID.randomUUID().toString();

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM processed_messages WHERE message_id = ?", messageId);
        data.cleanUp();
    }

    @Test
    void cargoCreatedEventShipsOrderOnce() {
        data = new ShipmentTestData(users, orders, payments, shipments, jdbc);
        Order order = data.order(data.user(Role.USER), OrderStatus.PREPARING);
        data.payment(order, PaymentStatus.PAID);
        data.shipment(order, ShipmentStatus.REQUESTED, 1);
        String body = ShipmentEventListenerTest.CREATED_FROM_CARGO
                .replace("\"orderId\":42", "\"orderId\":" + order.getId())
                .replace("019ac0e2-5f1a-7c3b-9d2e-4f5a6b7c8d9e", messageId);

        listener.onMessage(message(body));

        OrderShipment shipment = shipments.findByOrderId(order.getId()).orElseThrow();
        assertEquals(ShipmentStatus.CREATED, shipment.getStatus());
        assertEquals("TK0000000001", shipment.getTrackingNumber());
        assertEquals(Instant.parse("2026-11-26T09:15:42.12Z"), shipment.getStatusAt());
        assertEquals(OrderStatus.SHIPPED, orders.findById(order.getId()).orElseThrow().getStatus());

        // Aynı mesaj tekrar gelir (en az bir kez teslim): durum değişmez.
        shipment.setStatus(ShipmentStatus.IN_TRANSIT);
        shipments.saveAndFlush(shipment);
        listener.onMessage(message(body));
        assertEquals(ShipmentStatus.IN_TRANSIT, shipments.findByOrderId(order.getId()).orElseThrow().getStatus());
    }

    private Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
