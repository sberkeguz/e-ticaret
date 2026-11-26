package com.sarikaya.e_ticaret.shipment;

import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.shipment.entity.OrderShipment;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kargo testleri için geçici kullanıcı / sipariş / ödeme / gönderi. Yalnız test konteynerindeki
 * veritabanına yazar; {@link #cleanUp()} oluşturduklarını (ve bu siparişlerin outbox satırlarını) siler.
 */
public class ShipmentTestData {
    public static final String PHONE = "05329876543";
    public static final String ADDRESS = "Gizlisokak No 7 Daire 3";

    private final UserRepository users;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final OrderShipmentRepository shipments;
    private final JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();

    public ShipmentTestData(UserRepository users, OrderRepository orders, PaymentRepository payments,
                            OrderShipmentRepository shipments, JdbcTemplate jdbc) {
        this.users = users;
        this.orders = orders;
        this.payments = payments;
        this.shipments = shipments;
        this.jdbc = jdbc;
    }

    public User user(Role role) {
        User user = users.saveAndFlush(User.builder()
                .username("kargo-" + UUID.randomUUID().toString().substring(0, 12)).password("test").role(role).build());
        userIds.add(user.getId());
        return user;
    }

    public Order order(User user, OrderStatus status) {
        return order(user, status, true);
    }

    /**
     * withItems=true: siparişe geçici bir test ürününden 2 adet eklenir (iptal stoğu bu ürüne geri ekler).
     */
    public Order order(User user, OrderStatus status, boolean withItems) {
        Order order = new Order();
        order.setUser(user);
        order.setFirstName("Ayşe");
        order.setLastName("Yılmaz");
        order.setPhone(PHONE);
        order.setCity("İstanbul");
        order.setDistrict("Kadıköy");
        order.setNeighborhood("Moda");
        order.setAddress(ADDRESS);
        order.setTotal(new BigDecimal("150.50"));
        order.setStatus(status);
        if (withItems) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            Long productId = jdbc.queryForObject("""
                    INSERT INTO products (name, price, stock, active, test_product)
                    VALUES ('Kargo test ürünü', 75.25, 10, true, true) RETURNING id""", Long.class);
            productIds.add(productId);
            item.setProductId(productId);
            item.setProductName("Test ürünü");
            item.setPrice(new BigDecimal("75.25"));
            item.setQuantity(2);
            order.getItems().add(item);
        }
        order = orders.saveAndFlush(order);
        orderIds.add(order.getId());
        return order;
    }

    public Payment payment(Order order, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setStatus(status);
        payment.setAttemptId(UUID.randomUUID());
        payment.setToken("iyz-" + UUID.randomUUID());
        return payments.saveAndFlush(payment);
    }

    public OrderShipment shipment(Order order, ShipmentStatus status, int requestNo) {
        OrderShipment shipment = new OrderShipment();
        shipment.setOrderId(order.getId());
        shipment.setRequestNo(requestNo);
        shipment.setStatus(status);
        shipment.setStatusAt(Instant.now());
        return shipments.saveAndFlush(shipment);
    }

    public void cleanUp() {
        for (Long orderId : orderIds) {
            jdbc.update("DELETE FROM outbox_messages WHERE type = 'ShipmentRequested' AND payload LIKE ?",
                    "%\"orderId\":" + orderId + ",%");
            jdbc.update("DELETE FROM order_shipments WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM payment_alerts WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM payments WHERE order_id = ?", orderId);
            orders.deleteById(orderId);
        }
        for (Long productId : productIds) {
            jdbc.update("DELETE FROM products WHERE id = ?", productId);
        }
        for (Long userId : userIds) {
            users.deleteById(userId);
        }
        orderIds.clear();
        productIds.clear();
        userIds.clear();
    }
}
