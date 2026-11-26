package com.sarikaya.e_ticaret.shipment.messaging;

import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.payment.outbox.OutboxMessage;
import com.sarikaya.e_ticaret.payment.outbox.OutboxMessageRepository;
import com.sarikaya.e_ticaret.payment.outbox.OutboxRelay;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Outbox'a yazılan ShipmentRequested JSON'u cargo-service sözleşmesine birebir uyar: alan adları ve JSON
 * tipleri cargo-service'in kabul ettiği örnekle aynı, ShipmentRequested.Validate kurallarından geçer,
 * tarih metin (sayı/dizi değil), tutar sayı.
 */
class ShipmentRequestedContractTest {
    private static final UUID ID = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void sampleFromCargoServicePassesTheRulesAndBrokenOnesDoNot() {
        assertEquals(List.of(), CargoShipmentContract.validate(json.readTree(CargoShipmentContract.SAMPLE)));
        assertEquals(List.of("recipient.address"), CargoShipmentContract.validate(json.readTree(
                CargoShipmentContract.SAMPLE.replace("Şair Nefi", "Şair\\u0000Nefi"))));
        assertEquals(List.of("occurredAt"), CargoShipmentContract.validate(json.readTree(
                CargoShipmentContract.SAMPLE.replace("\"2026-11-26T10:15:30.123Z\"", "1764152130.123"))));
    }

    @Test
    void outboxJsonMatchesCargoContract() {
        JsonNode written = writtenThroughOutbox(command());

        assertEquals(List.of(), CargoShipmentContract.validate(written), written.toString());
        assertEquals(CargoShipmentContract.shape(json.readTree(CargoShipmentContract.SAMPLE)),
                CargoShipmentContract.shape(written));
        assertTrue(written.get("occurredAt").isString(), "tarih metin olmalı");
        assertEquals(Instant.parse("2026-11-26T10:15:30.123Z"), Instant.parse(written.get("occurredAt").asString()));
        assertEquals(ID.toString(), written.get("messageId").asString());
        assertEquals("order-77", written.get("idempotencyKey").asString());
        assertEquals("PAID", written.get("paymentStatus").asString());
        assertEquals(3, written.get("requestNo").asInt());
        assertEquals(3, written.get("parcel").get("itemCount").asInt());
        JsonNode declared = written.get("parcel").get("declaredValue");
        assertTrue(declared.isNumber(), "tutar JSON sayısı olmalı");
        assertEquals(0, new BigDecimal("149.90").compareTo(declared.decimalValue()));
        assertEquals("Şair Nefi Sk. No:5 D:3", written.get("recipient").get("address").asString());
    }

    @Test
    void nullTraceIdIsAllowed() {
        ShipmentRequested withoutTrace = ShipmentRequested.forOrder(ID, order(), 1, null, Instant.now());

        assertEquals(List.of(), CargoShipmentContract.validate(writtenThroughOutbox(withoutTrace)));
    }

    @Test
    void recipientNeverPrintsPersonalData() {
        ShipmentRequested command = command();

        assertEquals("[gizli]", command.recipient().toString());
        assertFalse(command.toString().contains("05551234567"));
        assertFalse(command.toString().contains("Şair Nefi"));
    }

    @Test
    void recipientCheckMatchesCargoSafeText() {
        assertTrue(ShipmentRequested.Recipient.from(order()).problem() == null);
        for (String bad : new String[]{"a\u0000b", "a\u0007b", "a\u007Fb", "a\u0085b", "a�b", "a￾b",
                "a\uD800b", "a\uDC00b"}) {
            Order order = order();
            order.setAddress(bad);
            assertEquals(ShipmentRequested.RecipientProblem.UNSAFE_CHARACTER,
                    ShipmentRequested.Recipient.from(order).problem(), bad);
            assertFalse(CargoShipmentContract.isSafe(bad));
        }
        Order emoji = order();
        emoji.setAddress("Sk. 5 😀 satır\nsonu\tsekme");
        assertTrue(ShipmentRequested.Recipient.from(emoji).problem() == null);
        Order blank = order();
        blank.setPhone(" ");
        assertEquals(ShipmentRequested.RecipientProblem.MISSING, ShipmentRequested.Recipient.from(blank).problem());
    }

    private JsonNode writtenThroughOutbox(ShipmentRequested command) {
        OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
        OutboxRelay relay = new OutboxRelay(repository, mock(RabbitTemplate.class), mock(TransactionTemplate.class));
        relay.enqueue(ID, CargoTopology.EXCHANGE, CargoTopology.SHIPMENT_CREATE_ROUTING_KEY, ShipmentRequested.MESSAGE_TYPE, command);
        ArgumentCaptor<OutboxMessage> saved = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(saved.capture());
        return json.readTree(saved.getValue().getPayload());
    }

    private static ShipmentRequested command() {
        return ShipmentRequested.forOrder(ID, order(), 3, "4bf92f3577b34da6a3ce929d0e0e4736",
                Instant.parse("2026-11-26T10:15:30.123456789Z"));
    }

    private static Order order() {
        Order order = new Order();
        order.setId(77L);
        order.setFirstName("Çağrı");
        order.setLastName("Öztürk");
        order.setPhone("05551234567");
        order.setCity("İstanbul");
        order.setDistrict("Kadıköy");
        order.setNeighborhood("Moda");
        order.setAddress("Şair Nefi Sk. No:5 D:3");
        order.setTotal(new BigDecimal("149.90"));
        OrderItem first = new OrderItem();
        first.setQuantity(2);
        OrderItem second = new OrderItem();
        second.setQuantity(1);
        order.getItems().add(first);
        order.getItems().add(second);
        return order;
    }
}
