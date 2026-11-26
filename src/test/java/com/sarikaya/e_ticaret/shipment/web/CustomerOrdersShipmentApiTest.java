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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Müşterinin sipariş listesindeki kargo bilgisi (sözleşme 1.2, Güvenlik S1/S2): ayrı müşteri DTO'su,
 * yönetici alanları yok, REQUESTED/FAILED gönderi gösterilmez, başkasının siparişi görünmez.
 */
@IntegrationTest
class CustomerOrdersShipmentApiTest {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderShipmentRepository shipments;
    @Autowired private JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;
    private ShipmentTestData data;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        data = new ShipmentTestData(users, orders, payments, shipments, jdbc);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    @Test
    void customerSeesOnlyTrackableShipmentWithoutAdminFields() throws Exception {
        User owner = data.user(Role.USER);
        Order requested = data.order(owner, OrderStatus.PREPARING);
        data.payment(requested, PaymentStatus.PAID);
        data.shipment(requested, ShipmentStatus.REQUESTED, 1);

        Order shipped = data.order(owner, OrderStatus.SHIPPED);
        data.payment(shipped, PaymentStatus.PAID);
        OrderShipment created = data.shipment(shipped, ShipmentStatus.CREATED, 1);
        created.setCarrierCode("FAKE");
        created.setCarrierName("Test Kargo");
        created.setTrackingNumber("TRK-A2");
        shipments.saveAndFlush(created);

        Order failed = data.order(owner, OrderStatus.PREPARING);
        data.payment(failed, PaymentStatus.PAID);
        OrderShipment failedShipment = data.shipment(failed, ShipmentStatus.FAILED, 2);
        failedShipment.setFailureCode(OrderShipment.CARRIER_FAILED);
        failedShipment.setTrackingNumber("GIZLI-FAILED");
        shipments.saveAndFlush(failedShipment);

        String body = mvc.perform(get("/api/orders").with(user(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode list = json.readTree(body);
        assertTrue(byId(list, requested.getId()).get("shipment").isNull());
        assertTrue(byId(list, failed.getId()).get("shipment").isNull());
        JsonNode shown = byId(list, shipped.getId()).get("shipment");
        assertEquals(Set.of("status", "carrierName", "trackingNumber", "trackingUrl", "statusAt"), names(shown));
        assertEquals("CREATED", shown.get("status").asString());
        assertEquals("TRK-A2", shown.get("trackingNumber").asString());
        assertEquals("Test Kargo", shown.get("carrierName").asString());
        assertTrue(shown.get("trackingUrl").isNull());
        for (String forbidden : new String[]{"requestNo", "failureReason", "canRetry", "canShip", "GIZLI-FAILED",
                "cargoShipmentId", "failureCode"}) {
            assertFalse(body.contains(forbidden), forbidden + " müşteri cevabında olmamalı");
        }
    }

    @Test
    void otherCustomerCannotSeeTheOrder() throws Exception {
        User owner = data.user(Role.USER);
        User stranger = data.user(Role.USER);
        Order shipped = data.order(owner, OrderStatus.SHIPPED);
        OrderShipment created = data.shipment(shipped, ShipmentStatus.CREATED, 1);
        created.setTrackingNumber("TRK-SECRET");
        shipments.saveAndFlush(created);

        String body = mvc.perform(get("/api/orders").with(user(stranger)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("TRK-SECRET"));
        assertEquals(0, json.readTree(body).size());

        mvc.perform(get("/api/orders/" + shipped.getId() + "/details").with(user(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private static JsonNode byId(JsonNode list, long id) {
        for (JsonNode order : list) {
            if (order.get("id").asLong() == id) {
                return order;
            }
        }
        throw new AssertionError("sipariş listede yok: " + id);
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
