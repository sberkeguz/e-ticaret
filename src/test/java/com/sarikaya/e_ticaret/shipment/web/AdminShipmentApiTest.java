package com.sarikaya.e_ticaret.shipment.web;

import com.sarikaya.e_ticaret.IntegrationTest;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.outbox.OutboxRelay;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.shipment.ShipmentTestData;
import com.sarikaya.e_ticaret.shipment.entity.OrderShipment;
import com.sarikaya.e_ticaret.shipment.messaging.CargoShipmentContract;
import com.sarikaya.e_ticaret.shipment.entity.ShipmentStatus;
import com.sarikaya.e_ticaret.shipment.repository.OrderShipmentRepository;
import com.sarikaya.e_ticaret.user.entity.Role;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.rabbitmq.client.GetResponse;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Kargoya ver" ve "Tekrar dene" uçları (sözleşme 1.3/1.4): cevaplar, hatalar, outbox komutu, sır temizliği. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AdminShipmentApiTest {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderShipmentRepository shipments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private RabbitTemplate rabbit;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;
    private ShipmentTestData data;
    private User admin;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        data = new ShipmentTestData(users, orders, payments, shipments, jdbc);
        admin = data.user(Role.ADMIN);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    @Test
    void shipWritesRequestedShipmentAndCargoCommandInOneGo(CapturedOutput output) throws Exception {
        Order order = paidOrder(OrderStatus.PREPARING);

        mvc.perform(post("/api/admin/orders/" + order.getId() + "/shipment").with(user(admin)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(order.getId()))
                .andExpect(jsonPath("$.canShip").value(false))
                .andExpect(jsonPath("$.shipment.status").value("REQUESTED"))
                .andExpect(jsonPath("$.shipment.requestNo").value(1))
                .andExpect(jsonPath("$.shipment.canRetry").value(false));

        OrderShipment shipment = shipments.findByOrderId(order.getId()).orElseThrow();
        assertEquals(ShipmentStatus.REQUESTED, shipment.getStatus());
        assertEquals(1, shipment.getRequestNo());

        // RabbitMQ ayakta: komut işlem kaydedilince yayınlanır. cargo-service'in kuyruğundan okunan, cargo'nun
        // alacağı mesajın kendisidir.
        Received received = awaitCommand(order);
        Map<String, Object> row = outboxRows(order).getFirst();
        assertEquals(1, outboxRows(order).size());
        assertEquals("cargo", row.get("exchange"));
        assertEquals("commands.shipment.create", row.get("routing_key"));
        assertEquals("ShipmentRequested", row.get("type"));
        JsonNode command = received.body();
        assertEquals(row.get("id").toString(), received.messageId(), "AMQP messageId = outbox satır numarası");
        assertEquals(received.messageId(), command.get("messageId").asString(), "AMQP messageId = gövde messageId");
        assertEquals(List.of(), CargoShipmentContract.validate(command), command.toString());
        assertEquals(order.getId().longValue(), command.get("orderId").asLong());
        assertEquals(2, command.get("parcel").get("itemCount").asInt());
        assertEquals(ShipmentTestData.PHONE, command.get("recipient").get("phone").asString());

        // Yayın onayından sonra alıcı bilgisi outbox satırından silinir.
        Map<String, Object> published = awaitPublished(order);
        String payloadAfter = (String) published.get("payload");
        assertFalse(json.readTree(payloadAfter).has("recipient"));
        assertFalse(payloadAfter.contains(ShipmentTestData.PHONE));
        assertFalse(payloadAfter.contains(ShipmentTestData.ADDRESS));

        assertFalse(output.getAll().contains(ShipmentTestData.PHONE), "telefon loga girmemeli");
        assertFalse(output.getAll().contains(ShipmentTestData.ADDRESS), "adres loga girmemeli");
    }

    @Test
    void secondShipIsConflictAndNothingIsDuplicated() throws Exception {
        Order order = paidOrder(OrderStatus.PREPARING);
        mvc.perform(post("/api/admin/orders/" + order.getId() + "/shipment").with(user(admin)))
                .andExpect(status().isAccepted());

        mvc.perform(post("/api/admin/orders/" + order.getId() + "/shipment").with(user(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPMENT_EXISTS"))
                .andExpect(jsonPath("$.message").value("Bu sipariş zaten kargoya verildi."));

        assertEquals(1, outboxRows(order).size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_shipments WHERE order_id = ?",
                Integer.class, order.getId()));
    }

    @Test
    void ineligibleOrdersAreRejected() throws Exception {
        Order unpaid = data.order(data.user(Role.USER), OrderStatus.PREPARING);
        data.payment(unpaid, PaymentStatus.PENDING);
        Order shipped = paidOrder(OrderStatus.SHIPPED);
        Order cancelled = paidOrder(OrderStatus.CANCELLED);
        Order legacy = data.order(data.user(Role.USER), OrderStatus.PREPARING); // ödeme kaydı yok

        for (Order order : List.of(unpaid, shipped, cancelled, legacy)) {
            mvc.perform(post("/api/admin/orders/" + order.getId() + "/shipment").with(user(admin)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_ALLOWED"));
            assertTrue(shipments.findByOrderId(order.getId()).isEmpty());
            assertTrue(outboxRows(order).isEmpty());
        }

        mvc.perform(post("/api/admin/orders/987654321/shipment").with(user(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Sipariş bulunamadı."));
    }

    @Test
    void addressWithCharacterCargoRejectsIsRefusedUpFront() throws Exception {
        Order order = paidOrder(OrderStatus.PREPARING);
        // Kontrol karakteri (BEL): PostgreSQL saklar, cargo-service komutu reddederdi.
        order.setAddress("Adres\u0007bozuk");
        orders.saveAndFlush(order);

        mvc.perform(post("/api/admin/orders/" + order.getId() + "/shipment").with(user(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Alıcı adresinde geçersiz karakter var; adresi düzeltin."));

        assertTrue(shipments.findByOrderId(order.getId()).isEmpty());
        assertTrue(outboxRows(order).isEmpty());
    }

    @Test
    void retryOfFailedShipmentRequestsAgainWithNextRequestNo() throws Exception {
        Order order = paidOrder(OrderStatus.PREPARING);
        OrderShipment failed = data.shipment(order, ShipmentStatus.FAILED, 1);
        failed.setFailureCode(OrderShipment.CARRIER_FAILED);
        failed.setLastEventSequence(2);
        shipments.saveAndFlush(failed);

        mvc.perform(post("/api/admin/orders/" + order.getId() + "/shipment/retry").with(user(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRequestNo\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.shipment.status").value("REQUESTED"))
                .andExpect(jsonPath("$.shipment.requestNo").value(2))
                .andExpect(jsonPath("$.shipment.failureReason").isEmpty());

        OrderShipment after = shipments.findByOrderId(order.getId()).orElseThrow();
        assertEquals(ShipmentStatus.REQUESTED, after.getStatus());
        assertEquals(2, after.getRequestNo());
        assertEquals(null, after.getFailureCode());
        assertEquals(2, after.getLastEventSequence(), "tekrar denemede sıra sıfırlanmaz");
        assertEquals(1, outboxRows(order).size());
        JsonNode command = awaitCommand(order).body();
        assertEquals(2, command.get("requestNo").asInt());
        assertEquals(List.of(), CargoShipmentContract.validate(command));

        // Aynı (artık eski) requestNo ile ikinci tıklama: 409, yeni komut yok.
        mvc.perform(post("/api/admin/orders/" + order.getId() + "/shipment/retry").with(user(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRequestNo\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_RETRYABLE"));
        assertEquals(1, outboxRows(order).size());
    }

    @Test
    void retryIsRefusedUnlessFailedAndExpected() throws Exception {
        Order requested = paidOrder(OrderStatus.PREPARING);
        data.shipment(requested, ShipmentStatus.REQUESTED, 1);
        Order failedWrongNo = paidOrder(OrderStatus.PREPARING);
        data.shipment(failedWrongNo, ShipmentStatus.FAILED, 2);
        Order failedCancelled = paidOrder(OrderStatus.CANCELLED);
        data.shipment(failedCancelled, ShipmentStatus.FAILED, 1);
        Order withoutShipment = paidOrder(OrderStatus.PREPARING);

        for (Order order : List.of(requested, failedWrongNo, failedCancelled, withoutShipment)) {
            mvc.perform(post("/api/admin/orders/" + order.getId() + "/shipment/retry").with(user(admin))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRequestNo\":1}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_RETRYABLE"));
            assertTrue(outboxRows(order).isEmpty());
        }

        String path = "/api/admin/orders/" + failedWrongNo.getId() + "/shipment/retry";
        mvc.perform(post(path).with(user(admin)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post(path).with(user(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRequestNo\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post(path).with(user(admin)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/orders/987654321/shipment/retry").with(user(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRequestNo\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void customerCannotUseShipmentEndpoints() throws Exception {
        Order order = paidOrder(OrderStatus.PREPARING);
        User customer = users.findById(order.getUser().getId()).orElseThrow();

        mvc.perform(post("/api/admin/orders/" + order.getId() + "/shipment").with(user(customer)))
                .andExpect(status().isForbidden());
        assertTrue(shipments.findByOrderId(order.getId()).isEmpty());
    }

    @Test
    void eachRequestGetsItsOwnMessageId() throws Exception {
        Order first = paidOrder(OrderStatus.PREPARING);
        Order second = paidOrder(OrderStatus.PREPARING);
        mvc.perform(post("/api/admin/orders/" + first.getId() + "/shipment").with(user(admin))).andExpect(status().isAccepted());
        mvc.perform(post("/api/admin/orders/" + second.getId() + "/shipment").with(user(admin))).andExpect(status().isAccepted());

        assertNotEquals(outboxRows(first).getFirst().get("id"), outboxRows(second).getFirst().get("id"));
    }

    private record Received(String messageId, JsonNode body) {
    }

    /** cargo.shipment-requests kuyruğundan bu siparişin komutunu bekler (başka testlerin komutları atlanır). */
    private Received awaitCommand(Order order) throws InterruptedException {
        outboxRelay.publishPending();
        for (int i = 0; i < 100; i++) {
            Received found = rabbit.execute(channel -> {
                GetResponse got;
                while ((got = channel.basicGet("cargo.shipment-requests", true)) != null) {
                    JsonNode body = json.readTree(new String(got.getBody(), StandardCharsets.UTF_8));
                    if (body.path("orderId").asLong() == order.getId()) {
                        return new Received(got.getProps().getMessageId(), body);
                    }
                }
                return null;
            });
            if (found != null) {
                return found;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("komut cargo.shipment-requests kuyruğuna gelmedi");
    }

    private Map<String, Object> awaitPublished(Order order) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Map<String, Object> row = outboxRows(order).getFirst();
            if (row.get("published_at") != null) {
                return row;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("outbox satırı yayınlanmadı");
    }

    private Order paidOrder(OrderStatus status) {
        Order order = data.order(data.user(Role.USER), status);
        data.payment(order, PaymentStatus.PAID);
        return order;
    }

    private List<Map<String, Object>> outboxRows(Order order) {
        return jdbc.queryForList("""
                SELECT id, exchange, routing_key, type, payload, published_at FROM outbox_messages
                WHERE type = 'ShipmentRequested' AND payload LIKE ? ORDER BY created_at""",
                "%\"orderId\":" + order.getId() + ",%");
    }
}
