package com.sarikaya.e_ticaret.shipment.messaging;

import com.sarikaya.e_ticaret.payment.events.MessageRetries;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * backend.shipment-events dinleyicisi. Okunamayan, zorunlu alanı eksik, numarasız (messageId) ya da saklanan
 * metninde yasak karakter (SafeText) olan mesaj beklemekle düzelmez → DLQ. İşleyicinin beklenmedik hatası (ör. veritabanına ulaşılamadı) → kargo
 * rotasının bekleme kuyruklarıyla tekrar deneme. Gövde loglanmaz.
 */
@Component
public class ShipmentEventListener {
    private final ShipmentEventHandler handler;
    private final MessageRetries retries;
    private final ObjectMapper json;

    public ShipmentEventListener(ShipmentEventHandler handler, MessageRetries retries, ObjectMapper json) {
        this.handler = handler;
        this.retries = retries;
        this.json = json;
    }

    @RabbitListener(id = "shipment-events", queues = CargoTopology.EVENTS_QUEUE)
    public void onMessage(Message message) {
        try {
            ShipmentEvent event = parse(message);
            String messageId = messageId(message, event);
            requireSafeStoredText(messageId, event);
            handler.handle(messageId, event);
        } catch (ShipmentEventException failure) {
            retries.handleFailure(message, failure, failure.retryable(), CargoTopology.EVENTS_ROUTE);
        } catch (RuntimeException failure) {
            retries.handleFailure(message, failure, true, CargoTopology.EVENTS_ROUTE);
        }
    }

    private ShipmentEvent parse(Message message) {
        ShipmentEvent event;
        try {
            event = json.readValue(message.getBody(), ShipmentEvent.class);
        } catch (JacksonException unreadable) {
            // Hata metni yalnız konum/tip bilgisi içerir; gövdeyi yazmaz.
            throw ShipmentEventException.deadLetter("Gönderi olayı okunamadı: " + unreadable.getOriginalMessage());
        }
        ShipmentEvent.Data data = event.data();
        if (event.sequence() == null || data == null || data.orderId() == null || data.requestNo() == null
                || data.status() == null || data.statusAt() == null) {
            throw ShipmentEventException.deadLetter("Gönderi olayında zorunlu alanlar eksik.");
        }
        return event;
    }

    /**
     * Veritabanına yazılan metinlerde (messageId, type, carrierCode, carrierName, trackingNumber) {@link SafeText}
     * yasak karakteri varsa olay her denemede aynı yerde düşer (ör. PostgreSQL NUL'u reddeder) → DLQ.
     * failureReason saklanmadığı için denetlenmez. Hata metni alan adını söyler, değeri yazmaz.
     */
    private static void requireSafeStoredText(String messageId, ShipmentEvent event) {
        ShipmentEvent.Data data = event.data();
        String[][] fields = {{"messageId", messageId}, {"type", event.type()}, {"carrierCode", data.carrierCode()},
                {"carrierName", data.carrierName()}, {"trackingNumber", data.trackingNumber()}};
        for (String[] field : fields) {
            if (!SafeText.isSafe(field[1])) {
                throw ShipmentEventException.deadLetter("Gönderi olayında " + field[0] + " izin verilmeyen karakter içeriyor.");
            }
        }
    }

    /** Asıl numara AMQP messageId özelliğidir; yoksa gövdedekine bakılır. */
    private static String messageId(Message message, ShipmentEvent event) {
        String fromProperties = message.getMessageProperties().getMessageId();
        if (fromProperties != null && !fromProperties.isBlank()) {
            return fromProperties;
        }
        if (event.messageId() != null && !event.messageId().isBlank()) {
            return event.messageId();
        }
        throw ShipmentEventException.deadLetter("Gönderi olayının numarası (messageId) yok; tekrarları ayıklanamaz.");
    }
}
