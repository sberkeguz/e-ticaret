package com.sarikaya.e_ticaret.shipment.web;

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
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Yönetici sipariş listesi ve durum değişikliği cevabı kargo alanlarını taşır (sözleşme 1.1). */
@IntegrationTest
class AdminOrdersShipmentApiTest {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderShipmentRepository shipments;
    @Autowired private JdbcTemplate jdbc;

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
    void listCarriesCanShipAndAdminShipment() throws Exception {
        User customer = data.user(Role.USER);
        Order ready = data.order(customer, OrderStatus.PREPARING);
        data.payment(ready, PaymentStatus.PAID);
        Order unpaid = data.order(customer, OrderStatus.PREPARING);
        data.payment(unpaid, PaymentStatus.PENDING);
        Order failed = data.order(customer, OrderStatus.PREPARING);
        data.payment(failed, PaymentStatus.PAID);
        OrderShipment failedShipment = data.shipment(failed, ShipmentStatus.FAILED, 3);
        failedShipment.setFailureCode(OrderShipment.PAYMENT_NOT_CONFIRMED);
        shipments.saveAndFlush(failedShipment);

        String body = mvc.perform(get("/api/admin/orders").param("user", customer.getUsername()).with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode items = json.readTree(body).get("items");
        assertEquals(3, items.size());
        JsonNode readyJson = byId(items, ready.getId());
        assertTrue(readyJson.get("canShip").asBoolean());
        assertTrue(readyJson.get("shipment").isNull());
        assertFalse(byId(items, unpaid.getId()).get("canShip").asBoolean());
        JsonNode failedJson = byId(items, failed.getId());
        assertFalse(failedJson.get("canShip").asBoolean());
        JsonNode shipment = failedJson.get("shipment");
        assertEquals("FAILED", shipment.get("status").asString());
        assertEquals(3, shipment.get("requestNo").asInt());
        assertTrue(shipment.get("canRetry").asBoolean());
        assertEquals("Kargo servisi ödemeyi onaylı görmedi; ödemeyi kontrol edip tekrar deneyin.",
                shipment.get("failureReason").asString());
        assertFalse(body.contains("PAYMENT_NOT_CONFIRMED"), "iç kod dönmemeli");
    }

    /**
     * Ö3: teslimat bilgisi cargo-service'in kabul etmeyeceği siparişte liste canShip=false döner ve
     * "Kargoya ver" de 409 verir (düğme gösterilip reddedilmez). Yeni alan eklenmez.
     */
    @Test
    void canShipFalseWhenShipmentRequestWouldBeRejected() throws Exception {
        User customer = data.user(Role.USER);
        Order blankName = data.order(customer, OrderStatus.PREPARING);
        blankName.setFirstName("   ");
        orders.saveAndFlush(blankName);
        data.payment(blankName, PaymentStatus.PAID);
        Order unsafeAddress = data.order(customer, OrderStatus.PREPARING);
        unsafeAddress.setAddress("Sokak\u0001 No 1");
        orders.saveAndFlush(unsafeAddress);
        data.payment(unsafeAddress, PaymentStatus.PAID);
        Order noItems = data.order(customer, OrderStatus.PREPARING, false);
        data.payment(noItems, PaymentStatus.PAID);

        String body = mvc.perform(get("/api/admin/orders").param("user", customer.getUsername()).with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode items = json.readTree(body).get("items");
        assertEquals(3, items.size());
        for (Order order : new Order[]{blankName, unsafeAddress, noItems}) {
            JsonNode node = byId(items, order.getId());
            assertFalse(node.get("canShip").asBoolean(), "kargoya verilemeyecek sipariş: " + order.getId());
            assertTrue(node.get("shipment").isNull());
            mvc.perform(post("/api/admin/orders/" + order.getId() + "/shipment").with(user(admin)))
                    .andExpect(status().isConflict());
        }
    }

    @Test
    void statusChangeAnswerCarriesShipmentFields() throws Exception {
        User customer = data.user(Role.USER);
        Order ready = data.order(customer, OrderStatus.PREPARING);
        data.payment(ready, PaymentStatus.PAID);

        mvc.perform(patch("/api/admin/orders/" + ready.getId() + "/status").with(user(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PREPARING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ready.getId()))
                .andExpect(jsonPath("$.canShip").value(true))
                .andExpect(jsonPath("$.shipment").isEmpty());
    }

    private static JsonNode byId(JsonNode list, long id) {
        for (JsonNode order : list) {
            if (order.get("id").asLong() == id) {
                return order;
            }
        }
        throw new AssertionError("sipariş listede yok: " + id);
    }
}
