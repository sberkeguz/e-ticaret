package com.sarikaya.e_ticaret.payment.outbox;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Yayın onayından sonra outbox satırında sır kalmaz: ödeme komutunun kartı (request.card) ve kargo
 * komutunun alıcı bilgisi (kökteki recipient) silinir; geri kalan alanlar aynen durur.
 */
class OutboxRelaySecretsTest {
    private final OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
    private final OutboxRelay relay = new OutboxRelay(repository, mock(RabbitTemplate.class), mock(TransactionTemplate.class));
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void cardIsRemovedFromChargeCommand() {
        String cleaned = relay.withoutSecrets("""
                {"type":"ChargeRequested","request":{"amount":10,"card":{"number":"5528790000000008"}}}""");

        JsonNode root = json.readTree(cleaned);
        assertFalse(root.get("request").has("card"));
        assertEquals(10, root.get("request").get("amount").asInt());
        assertFalse(cleaned.contains("5528790000000008"));
    }

    @Test
    void recipientIsRemovedFromShipmentCommand() {
        String cleaned = relay.withoutSecrets("""
                {"type":"ShipmentRequested","orderId":7,"recipient":{"phone":"05551234567","address":"Gizli sk. 1"},
                 "parcel":{"itemCount":2,"declaredValue":10.50}}""");

        JsonNode root = json.readTree(cleaned);
        assertFalse(root.has("recipient"));
        assertEquals(7, root.get("orderId").asInt());
        assertEquals(2, root.get("parcel").get("itemCount").asInt());
        assertFalse(cleaned.contains("05551234567"));
        assertFalse(cleaned.contains("Gizli"));
    }

    @Test
    void payloadWithoutSecretsIsUnchanged() {
        String payload = "{\"type\":\"Other\",\"data\":{\"recipient\":\"iç alan silinmez\"}}";

        assertEquals(payload, relay.withoutSecrets(payload));
    }

    @Test
    void enqueueWithIdUsesItAsRowIdAndAmqpMessageId() {
        UUID id = UUID.fromString("018f0000-0000-7000-8000-000000000001");

        relay.enqueue(id, "cargo", "commands.shipment.create", "ShipmentRequested", Map.of("messageId", id.toString()));

        ArgumentCaptor<OutboxMessage> saved = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(saved.capture());
        OutboxMessage row = saved.getValue();
        assertEquals(id, row.getId());
        assertEquals("cargo", row.getExchange());
        assertEquals("commands.shipment.create", row.getRoutingKey());
        assertEquals("ShipmentRequested", row.getType());
        assertTrue(row.getPayload().contains(id.toString()));

        row.setCreatedAt(Instant.parse("2026-11-26T10:00:00Z"));
        Message amqp = OutboxRelay.toAmqp(row);
        assertEquals(id.toString(), amqp.getMessageProperties().getMessageId());
        assertEquals("ShipmentRequested", amqp.getMessageProperties().getType());
    }
}
