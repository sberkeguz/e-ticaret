package com.sarikaya.e_ticaret.shipment.messaging;

import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.payment.events.MessageRetries;
import com.sarikaya.e_ticaret.payment.outbox.OutboxMessage;
import com.sarikaya.e_ticaret.payment.outbox.OutboxMessageRepository;
import com.sarikaya.e_ticaret.payment.outbox.OutboxRelay;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * QA: iki dilli sözleşme kontrolü. Mirror (CargoShipmentContract) yerine gerçek cargo-service koduyla
 * karşılaştırma dosya üzerinden yapılır:
 *  - backend'in KABUL ettiği komutlar target/cargo-contract/backend-commands/*.json'a yazılır; cargo-service
 *    testi BackendContractFilesTests bunları gerçek ShipmentRequested.Validate() ile doğrular.
 *  - cargo-service testi gerçek ShipmentEvents.Append çıktısını target/cargo-contract/cargo-events/*.json'a
 *    yazar; burada gerçek ShipmentEventListener ile okunur.
 */
class CargoCrossContractQaTest {
    static final Path CONTRACT_DIR = Path.of("target", "cargo-contract");
    private static final UUID ID = UUID.fromString("018f0000-0000-7000-8000-0000000000bb");

    /**
     * HATA: Java String.isBlank() U+00A0 / U+2007 / U+202F'yi boşluk saymaz; cargo-service
     * string.IsNullOrWhiteSpace() sayar. Backend "Kargoya ver"i kabul eder, cargo-service komutu DLQ'ya atar,
     * gönderi REQUESTED'da takılır (iptal de edilemez: ORDER_HAS_SHIPMENT).
     */
    @Test
    void recipientFieldOfOnlyUnicodeSpacesIsMissingLikeCargoService() {
        for (String space : new String[]{" ", " ", " ", "   "}) {
            Order order = order();
            order.setDistrict(space);
            assertEquals(ShipmentRequested.RecipientProblem.MISSING,
                    ShipmentRequested.Recipient.from(order).problem(),
                    "U+" + Integer.toHexString(space.trim().isEmpty() ? space.codePointAt(0) : space.trim().codePointAt(0)));
        }
    }

    /** Backend'in kabul ettiği (problem()==null) uç durum komutlarını cargo-service testine bırakır. */
    @Test
    void writesBackendAcceptedCommandsForCargoService() throws IOException {
        Map<String, Order> cases = new LinkedHashMap<>();
        cases.put("plain", order());
        Order turkishEmoji = order();
        turkishEmoji.setAddress("Gül sk. No:5\r\nDaire 3\t🙂 İçerenköy ĞÜŞİÖÇ");
        cases.put("turkish-emoji-newline", turkishEmoji);
        Order zeroTotal = order();
        zeroTotal.setTotal(new BigDecimal("0.00"));
        cases.put("zero-total", zeroTotal);
        Order bigTotal = order();
        bigTotal.setTotal(new BigDecimal("99999999.99"));
        cases.put("big-total", bigTotal);
        Order longText = order();
        longText.setAddress("A".repeat(500));
        longText.setFirstName("B".repeat(100));
        cases.put("long-text", longText);
        Order nbsp = order();
        nbsp.setDistrict(" ");
        cases.put("nbsp-district", nbsp);
        Order narrowNbsp = order();
        narrowNbsp.setNeighborhood(" ");
        cases.put("narrow-nbsp-neighborhood", narrowNbsp);

        Path dir = Files.createDirectories(CONTRACT_DIR.resolve("backend-commands"));
        try (Stream<Path> old = Files.list(dir)) {
            for (Path file : old.toList()) {
                Files.delete(file);
            }
        }
        int written = 0;
        for (Map.Entry<String, Order> entry : cases.entrySet()) {
            ShipmentRequested command = ShipmentRequested.forOrder(ID, entry.getValue(), 2,
                    "4bf92f3577b34da6a3ce929d0e0e4736", Instant.parse("2026-11-26T10:15:30Z"));
            boolean backendAccepts = command.recipient().problem() == null && command.parcel().valid();
            if (backendAccepts) {
                Files.writeString(dir.resolve(entry.getKey() + ".json"), writtenThroughOutbox(command),
                        StandardCharsets.UTF_8);
                written++;
            }
        }
        assertTrue(written >= 5, "en az 5 örnek yazılmalı");
    }

    /** cargo-service'in gerçek Append çıktısı (dotnet test önce çalışmalı) backend dinleyicisinden geçer. */
    @Test
    void eventsWrittenByRealCargoServiceAreAccepted() throws IOException {
        Path dir = CONTRACT_DIR.resolve("cargo-events");
        assumeTrue(Files.isDirectory(dir), "Önce cargo-service testlerini çalıştırın (dosyalar yok): " + dir);
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assumeTrue(!files.isEmpty(), "cargo-events boş");

        ShipmentEventHandler handler = mock(ShipmentEventHandler.class);
        MessageRetries retries = mock(MessageRetries.class);
        ShipmentEventListener listener = new ShipmentEventListener(handler, retries, JsonMapper.builder().build());
        Set<String> statuses = new java.util.TreeSet<>();
        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            reset(handler, retries);
            byte[] body = Files.readAllBytes(file);
            MessageProperties properties = new MessageProperties();
            properties.setMessageId(file.getFileName().toString());
            listener.onMessage(new Message(body, properties));
            try {
                verify(retries, never()).handleFailure(any(), any(), anyBoolean(), any());
                ArgumentCaptor<ShipmentEvent> captured = ArgumentCaptor.forClass(ShipmentEvent.class);
                verify(handler).handle(any(), captured.capture());
                ShipmentEvent event = captured.getValue();
                assertNotNull(event.occurredAt());
                assertNotNull(event.data().statusAt());
                statuses.add(event.data().status());
            } catch (AssertionError failure) {
                problems.add(file.getFileName() + ": " + failure.getMessage());
            }
        }
        assertEquals(List.of(), problems);
        assertEquals(Set.of("CREATED", "IN_TRANSIT", "DELIVERED", "DELIVERY_FAILED", "FAILED", "REJECTED"), statuses);
    }

    private static String writtenThroughOutbox(ShipmentRequested command) {
        OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
        OutboxRelay relay = new OutboxRelay(repository, mock(RabbitTemplate.class), mock(TransactionTemplate.class));
        relay.enqueue(ID, CargoTopology.EXCHANGE, CargoTopology.SHIPMENT_CREATE_ROUTING_KEY,
                ShipmentRequested.MESSAGE_TYPE, command);
        ArgumentCaptor<OutboxMessage> saved = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(saved.capture());
        return saved.getValue().getPayload();
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
        OrderItem item = new OrderItem();
        item.setQuantity(2);
        order.getItems().add(item);
        return order;
    }
}
