package com.sarikaya.e_ticaret.shipment.messaging;

import com.sarikaya.e_ticaret.payment.events.MessageRetries;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Dinleyici, cargo-service'in yazdığı gerçek olay gövdesini okur (System.Text.Json, JsonSerializerOptions.Web:
 * camelCase, DateTimeOffset "+00:00" ve en çok 7 haneli kesir, sondaki sıfırlar kırpılmış). Bozuk, eksik
 * ya da numarasız mesaj DLQ'ya; beklenmeyen hata tekrar denemeye gider (kargo rotası).
 */
class ShipmentEventListenerTest {
    /** cargo-service ShipmentEvents.Append çıktısı (gerçek biçim). */
    static final String CREATED_FROM_CARGO = """
            {"messageId":"019ac0e2-5f1a-7c3b-9d2e-4f5a6b7c8d9e","type":"ShipmentCreated","version":1,\
            "occurredAt":"2026-11-26T09:15:42.1234567+00:00","traceId":"4bf92f3577b34da6a3ce929d0e0e4736",\
            "sequence":1,"data":{"orderId":42,"shipmentId":"019ac0e2-5f00-7aaa-8bbb-ccccdddd0001","requestNo":1,\
            "status":"CREATED","carrierCode":"FAKE","carrierName":"Test Kargo","trackingNumber":"TK0000000001",\
            "statusAt":"2026-11-26T09:15:42.12+00:00","failureReason":null}}""";

    private final ShipmentEventHandler handler = mock(ShipmentEventHandler.class);
    private final MessageRetries retries = mock(MessageRetries.class);
    private final ShipmentEventListener listener = new ShipmentEventListener(handler, retries, JsonMapper.builder().build());

    @Test
    void realCargoEventIsParsed() {
        listener.onMessage(message(CREATED_FROM_CARGO, "019ac0e2-5f1a-7c3b-9d2e-4f5a6b7c8d9e"));

        ArgumentCaptor<ShipmentEvent> captured = ArgumentCaptor.forClass(ShipmentEvent.class);
        verify(handler).handle(eq("019ac0e2-5f1a-7c3b-9d2e-4f5a6b7c8d9e"), captured.capture());
        ShipmentEvent event = captured.getValue();
        assertEquals("ShipmentCreated", event.type());
        assertEquals(1, event.sequence());
        assertEquals(Instant.parse("2026-11-26T09:15:42.1234567Z"), event.occurredAt().toInstant());
        assertEquals(42L, event.data().orderId());
        assertEquals(UUID.fromString("019ac0e2-5f00-7aaa-8bbb-ccccdddd0001"), event.data().shipmentId());
        assertEquals(1, event.data().requestNo());
        assertEquals("CREATED", event.data().status());
        assertEquals("TK0000000001", event.data().trackingNumber());
        assertEquals(Instant.parse("2026-11-26T09:15:42.12Z"), event.data().statusAt().toInstant());
        assertNull(event.data().failureReason());
        verify(retries, never()).handleFailure(any(), any(), anyBoolean(), any());
    }

    @Test
    void unknownFieldsAreIgnored() {
        String withExtra = CREATED_FROM_CARGO.replace("\"sequence\":1,", "\"sequence\":1,\"yeniAlan\":{\"x\":1},");

        listener.onMessage(message(withExtra, "m-1"));

        verify(handler).handle(eq("m-1"), any());
    }

    @Test
    void bodyMessageIdIsUsedWhenAmqpHasNone() {
        listener.onMessage(message(CREATED_FROM_CARGO, null));

        verify(handler).handle(eq("019ac0e2-5f1a-7c3b-9d2e-4f5a6b7c8d9e"), any());
    }

    @Test
    void unreadableMessageGoesToDeadLetters() {
        Message broken = message("{bozuk", "m-1");

        listener.onMessage(broken);

        verify(handler, never()).handle(anyString(), any());
        verify(retries).handleFailure(eq(broken), any(), eq(false), eq(CargoTopology.EVENTS_ROUTE));
    }

    @Test
    void missingRequiredFieldsGoToDeadLetters() {
        for (String missing : new String[]{
                CREATED_FROM_CARGO.replace("\"sequence\":1,", ""),
                CREATED_FROM_CARGO.replace("\"requestNo\":1,", ""),
                CREATED_FROM_CARGO.replace("\"status\":\"CREATED\",", ""),
                CREATED_FROM_CARGO.replace("\"orderId\":42,", ""),
                CREATED_FROM_CARGO.replace("\"statusAt\":\"2026-11-26T09:15:42.12+00:00\",", ""),
                "{\"messageId\":\"x\",\"type\":\"ShipmentCreated\",\"sequence\":1}"}) {
            Message message = message(missing, "m-1");
            listener.onMessage(message);
            verify(retries).handleFailure(eq(message), any(), eq(false), eq(CargoTopology.EVENTS_ROUTE));
        }
        verify(handler, never()).handle(anyString(), any());
    }

    @Test
    void messageWithoutAnyIdGoesToDeadLetters() {
        Message message = message(CREATED_FROM_CARGO.replace("\"messageId\":\"019ac0e2-5f1a-7c3b-9d2e-4f5a6b7c8d9e\",", ""), null);

        listener.onMessage(message);

        verify(handler, never()).handle(anyString(), any());
        verify(retries).handleFailure(eq(message), any(), eq(false), eq(CargoTopology.EVENTS_ROUTE));
    }

    /** Saklanan metinde yasak karakter (SafeText) kalıcıdır: tekrar denenmez, işleyiciye gitmeden DLQ. */
    @Test
    void storedTextWithForbiddenCharacterGoesToDeadLetters() {
        // JSON kaçışları: NUL, BEL, C1 (U+0085), U+FFFD, eşsiz yüksek/düşük vekil.
        for (String bad : new String[]{"\\u0000", "\\u0007", "\\u0085", "\\ufffd", "\\ud800", "\\udc00"}) {
            for (String body : new String[]{
                    CREATED_FROM_CARGO.replace("\"TK0000000001\"", "\"TK" + bad + "1\""),
                    CREATED_FROM_CARGO.replace("\"Test Kargo\"", "\"Test" + bad + "Kargo\""),
                    CREATED_FROM_CARGO.replace("\"carrierCode\":\"FAKE\"", "\"carrierCode\":\"FA" + bad + "KE\""),
                    CREATED_FROM_CARGO.replace("\"type\":\"ShipmentCreated\"", "\"type\":\"Shipment" + bad + "\"")}) {
                Message message = message(body, "m-1");
                listener.onMessage(message);
                verify(retries).handleFailure(eq(message), any(), eq(false), eq(CargoTopology.EVENTS_ROUTE));
            }
        }
        Message badId = message(CREATED_FROM_CARGO, "m\u00001");
        listener.onMessage(badId);
        verify(retries).handleFailure(eq(badId), any(), eq(false), eq(CargoTopology.EVENTS_ROUTE));
        verify(handler, never()).handle(anyString(), any());
    }

    /** failureReason saklanmaz; içindeki karakter olayı engellemez. Emoji ve Türkçe harf serbesttir. */
    @Test
    void unstoredFailureReasonAndEmojiAreAccepted() {
        String body = CREATED_FROM_CARGO.replace("\"failureReason\":null", "\"failureReason\":\"x\\u0000y\"")
                .replace("\"Test Kargo\"", "\"Çağrı Kargo \\ud83d\\ude00\"");

        listener.onMessage(message(body, "m-1"));

        verify(handler).handle(eq("m-1"), any());
        verify(retries, never()).handleFailure(any(), any(), anyBoolean(), any());
    }

    @Test
    void handlerDeadLetterIsNotRetried() {
        doThrow(ShipmentEventException.deadLetter("bilinmeyen sipariş")).when(handler).handle(anyString(), any());
        Message message = message(CREATED_FROM_CARGO, "m-1");

        listener.onMessage(message);

        verify(retries).handleFailure(eq(message), any(), eq(false), eq(CargoTopology.EVENTS_ROUTE));
    }

    @Test
    void unexpectedFailureIsRetried() {
        doThrow(new IllegalStateException("veritabanı yok")).when(handler).handle(anyString(), any());
        Message message = message(CREATED_FROM_CARGO, "m-1");

        listener.onMessage(message);

        verify(retries).handleFailure(eq(message), any(), eq(true), eq(CargoTopology.EVENTS_ROUTE));
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    private static Message message(String body, String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
