package com.sarikaya.e_ticaret.shipment.messaging;

import com.sarikaya.e_ticaret.IntegrationTest;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.events.PaymentEventTopology;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.shipment.ShipmentTestData;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import com.sarikaya.e_ticaret.shipment.service.ShipmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * QA: gerçek RabbitMQ (Testcontainers) üzerinde kargo olay rotası uçtan uca.
 *  olay (cargo exchange, shipment.created) → dinleyici hatası → retry.1 → (TTL) ana kuyruk → retry.2;
 *  son tekrar hakkı → DLQ; okunamayan olay → doğrudan DLQ. Ayrıca "Kargoya ver" komutunun gerçek tel
 *  gövdesi (cargo.shipment-requests) ve yayından sonra outbox satırında recipient kalmaması.
 */
@IntegrationTest
class ShipmentEventRetryRouteE2EQaTest {
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired private RabbitListenerEndpointRegistry registry;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private ShipmentService shipmentService;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderShipmentRepository shipments;
    @Autowired private JdbcTemplate jdbc;

    private ShipmentTestData data;
    private final List<String> messageIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        data = new ShipmentTestData(users, orders, payments, shipments, jdbc);
        purgeAll();
    }

    @AfterEach
    void tearDown() {
        registry.getListenerContainer("shipment-events").stop();
        jdbc.execute("DROP TRIGGER IF EXISTS qa_fail_processed ON processed_messages");
        jdbc.execute("DROP FUNCTION IF EXISTS qa_fail_processed()");
        purgeAll();
        for (String id : messageIds) {
            jdbc.update("DELETE FROM processed_messages WHERE message_id = ?", id);
        }
        data.cleanUp();
    }

    @Test
    void failingEventGoesThroughRetryQueuesBackToMainQueueAndFinallyToDeadLetters() {
        Order order = data.order(data.user(Role.USER), OrderStatus.PREPARING);
        data.payment(order, PaymentStatus.PAID);
        data.shipment(order, ShipmentStatus.REQUESTED, 1);
        String messageId = UUID.randomUUID().toString();
        messageIds.add(messageId);
        // Bu mesajın processed_messages kaydını geçici tetikleyici reddeder: veritabanı hatası → beklenmeyen
        // hata → tekrar deneme rotası. (Yasak karakter artık kalıcı hata sayılıp doğrudan DLQ'ya gider.)
        failProcessedInsertFor(messageId);
        String body = ShipmentEventListenerTest.CREATED_FROM_CARGO
                .replace("\"orderId\":42", "\"orderId\":" + order.getId())
                .replace("TK0000000001", "TKGIZLI99");

        registry.getListenerContainer("shipment-events").start();
        rabbit.send(CargoTopology.EXCHANGE, "shipment.created", message(body, messageId, null));

        await("retry.1'e düşmeli", () -> count(CargoTopology.EVENTS_ROUTE.retryQueue(1)) == 1);
        // retry.1 TTL'i (5 sn) dolunca ana kuyruğa döner, yine patlar, retry.2'ye geçer.
        await("TTL sonrası retry.2'ye geçmeli", () -> count(CargoTopology.EVENTS_ROUTE.retryQueue(2)) == 1);
        assertEquals(0, count(CargoTopology.EVENTS_ROUTE.retryQueue(1)));
        assertEquals(0, count(CargoTopology.EVENTS_DEAD_LETTER_QUEUE));
        purge(CargoTopology.EVENTS_ROUTE.retryQueue(2));

        // Son bekleme kuyruğu: mesaj düzeyinde kısa TTL (kuyruk TTL'iyle küçüğü geçerli) → ana kuyruk → DLQ.
        int last = PaymentEventTopology.RETRY_DELAYS.size();
        rabbit.send("", CargoTopology.EVENTS_ROUTE.retryQueue(last), message(body, messageId, last));
        await("son tekrardan sonra DLQ'ya düşmeli", () -> count(CargoTopology.EVENTS_DEAD_LETTER_QUEUE) == 1);

        Message dead = rabbit.receive(CargoTopology.EVENTS_DEAD_LETTER_QUEUE, 2000);
        assertNotNull(dead);
        assertEquals(body, new String(dead.getBody(), StandardCharsets.UTF_8), "gövde değişmeden DLQ'ya gitmeli");
        assertEquals(messageId, dead.getMessageProperties().getMessageId());
        Map<String, Object> headers = dead.getMessageProperties().getHeaders();
        assertEquals(last, ((Number) headers.get("x-retry-count")).intValue());
        assertNotNull(headers.get("x-failed-at"));
        String error = String.valueOf(headers.get("x-error"));
        assertFalse(error.contains("GIZLI99"), "hata başlığı olay içeriğini taşımamalı: " + error);

        // Uygulanmadı ve "işlendi" olarak da işaretlenmedi.
        assertEquals(ShipmentStatus.REQUESTED, shipments.findByOrderId(order.getId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.PREPARING, orders.findById(order.getId()).orElseThrow().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM processed_messages WHERE message_id = ?",
                Integer.class, messageId));
    }

    /** Yalnız verilen messageId'nin processed_messages'a yazılmasını veritabanı hatasıyla düşürür. */
    private void failProcessedInsertFor(String messageId) {
        UUID.fromString(messageId); // SQL'e yalnız UUID girer
        jdbc.execute("CREATE OR REPLACE FUNCTION qa_fail_processed() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.message_id = '" + messageId + "' THEN RAISE EXCEPTION 'qa: veritabani hatasi'; END IF; "
                + "RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER qa_fail_processed BEFORE INSERT ON processed_messages "
                + "FOR EACH ROW EXECUTE FUNCTION qa_fail_processed()");
    }

    /** Saklanan metinde NUL kalıcı hatadır: tekrar denenmeden DLQ, gönderi ve "işlendi" kaydı değişmez. */
    @Test
    void eventWithNulInTrackingNumberGoesStraightToDeadLetters() {
        Order order = data.order(data.user(Role.USER), OrderStatus.PREPARING);
        data.payment(order, PaymentStatus.PAID);
        data.shipment(order, ShipmentStatus.REQUESTED, 1);
        String messageId = UUID.randomUUID().toString();
        messageIds.add(messageId);
        String body = ShipmentEventListenerTest.CREATED_FROM_CARGO
                .replace("\"orderId\":42", "\"orderId\":" + order.getId())
                .replace("TK0000000001", "TK\\u0000GIZLI99");

        registry.getListenerContainer("shipment-events").start();
        rabbit.send(CargoTopology.EXCHANGE, "shipment.created", message(body, messageId, null));

        await("DLQ'ya düşmeli", () -> count(CargoTopology.EVENTS_DEAD_LETTER_QUEUE) == 1);
        for (int attempt = 1; attempt <= PaymentEventTopology.RETRY_DELAYS.size(); attempt++) {
            assertEquals(0, count(CargoTopology.EVENTS_ROUTE.retryQueue(attempt)));
        }
        Message dead = rabbit.receive(CargoTopology.EVENTS_DEAD_LETTER_QUEUE, 2000);
        assertNotNull(dead);
        String error = String.valueOf(dead.getMessageProperties().getHeaders().get("x-error"));
        assertFalse(error.contains("GIZLI99"), "hata başlığı olay içeriğini taşımamalı: " + error);
        assertEquals(ShipmentStatus.REQUESTED, shipments.findByOrderId(order.getId()).orElseThrow().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM processed_messages WHERE message_id = ?",
                Integer.class, messageId));
    }

    @Test
    void unreadableEventGoesStraightToDeadLettersWithoutRetry() {
        String messageId = UUID.randomUUID().toString();
        registry.getListenerContainer("shipment-events").start();
        rabbit.send(CargoTopology.EXCHANGE, "shipment.created", message("{bozuk", messageId, null));

        await("DLQ'ya düşmeli", () -> count(CargoTopology.EVENTS_DEAD_LETTER_QUEUE) == 1);
        for (int attempt = 1; attempt <= PaymentEventTopology.RETRY_DELAYS.size(); attempt++) {
            assertEquals(0, count(CargoTopology.EVENTS_ROUTE.retryQueue(attempt)));
        }
    }

    @Test
    void retryQueuesHaveExpectedTtlAndPointBackToMainQueue() {
        for (int attempt = 1; attempt <= PaymentEventTopology.RETRY_DELAYS.size(); attempt++) {
            String queue = CargoTopology.EVENTS_ROUTE.retryQueue(attempt);
            // Yanlış argümanla pasif olmayan bildirim PRECONDITION_FAILED verir; aynı argümanlar sessiz geçer.
            long ttl = PaymentEventTopology.RETRY_DELAYS.get(attempt - 1).toMillis();
            rabbit.execute(channel -> channel.queueDeclare(queue, true, false, false, Map.of(
                    "x-message-ttl", (int) ttl,
                    "x-dead-letter-exchange", "",
                    "x-dead-letter-routing-key", CargoTopology.EVENTS_QUEUE)));
        }
    }

    @Test
    void shipWithDistrictOfOnlyNoBreakSpaceIsRefusedUpFront() {
        // Müşteri formu @NotBlank'ten geçer (Hibernate trim() yalnız <= U+0020'yi kırpar); cargo-service
        // string.IsNullOrWhiteSpace ile reddeder → komut DLQ, gönderi REQUESTED'da takılı, iptal 409.
        Order order = data.order(data.user(Role.USER), OrderStatus.PREPARING);
        order.setDistrict(" ");
        orders.saveAndFlush(order);
        data.payment(order, PaymentStatus.PAID);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.sarikaya.e_ticaret.shipment.exception.ConflictException.class,
                () -> shipmentService.request(order.getId()),
                "cargo-service'in reddedeceği komut yazılmamalı");
        assertTrue(shipments.findByOrderId(order.getId()).isEmpty(), "gönderi satırı oluşmamalı");
    }

    @Test
    void shipCommandOnTheWireHasRecipientButOutboxRowNoLongerDoes() throws Exception {
        Order order = data.order(data.user(Role.USER), OrderStatus.PREPARING);
        data.payment(order, PaymentStatus.PAID);

        shipmentService.request(order.getId());

        Message wire = null;
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (wire == null && System.nanoTime() < deadline) {
            wire = rabbit.receive(CargoTopology.SHIPMENT_REQUESTS_QUEUE, 500);
        }
        assertNotNull(wire, "komut cargo.shipment-requests'e ulaşmalı");
        String wireBody = new String(wire.getBody(), StandardCharsets.UTF_8);
        assertTrue(wireBody.contains(ShipmentTestData.ADDRESS), "cargo-service alıcıyı almalı");
        Path dir = Files.createDirectories(CargoCrossContractQaTest.CONTRACT_DIR.resolve("backend-commands"));
        Files.writeString(dir.resolve("wire-from-outbox.json"), wireBody, StandardCharsets.UTF_8);

        await("outbox satırı yayınlandı olmalı", () -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT bool_and(published_at IS NOT NULL) FROM outbox_messages WHERE type = 'ShipmentRequested'"
                        + " AND payload LIKE ?", Boolean.class, "%\"orderId\":" + order.getId() + ",%")));
        String stored = jdbc.queryForObject("SELECT payload FROM outbox_messages WHERE type = 'ShipmentRequested'"
                + " AND payload LIKE ?", String.class, "%\"orderId\":" + order.getId() + ",%");
        assertFalse(stored.contains("recipient"), "yayından sonra recipient silinmeli");
        assertFalse(stored.contains(ShipmentTestData.PHONE));
        assertFalse(stored.contains(ShipmentTestData.ADDRESS));
    }

    private static Message message(String body, String messageId, Integer retryCount) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        properties.setContentType("application/json");
        if (retryCount != null) {
            properties.setHeader("x-retry-count", retryCount);
            properties.setExpiration("100");
        }
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    private long count(String queue) {
        Long value = rabbit.execute(channel -> (long) channel.queueDeclarePassive(queue).getMessageCount());
        return value == null ? 0 : value;
    }

    private void purge(String queue) {
        rabbit.execute(channel -> channel.queuePurge(queue));
    }

    private void purgeAll() {
        purge(CargoTopology.EVENTS_QUEUE);
        purge(CargoTopology.EVENTS_DEAD_LETTER_QUEUE);
        purge(CargoTopology.SHIPMENT_REQUESTS_QUEUE);
        for (int attempt = 1; attempt <= PaymentEventTopology.RETRY_DELAYS.size(); attempt++) {
            purge(CargoTopology.EVENTS_ROUTE.retryQueue(attempt));
        }
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail(what);
            }
        }
        fail("Zaman aşımı: " + what);
    }
}
