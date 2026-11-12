package com.sarikaya.e_ticaret.payment.events;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * backend.payment-events kuyruğunu dinler.
 *
 * Akış: mesajı oku → işle (tek veritabanı işlemi) → metot dönünce Spring mesajı onaylar (ack).
 * Yani iş veritabanına yazılmadan mesaj "alındı" sayılmaz.
 * İşlenemezse mesaj tekrar deneme ya da DLQ kuyruğuna taşınır ve bu kopya onaylanır.
 */
@Component
public class PaymentEventListener {
    private final PaymentEventHandler handler;
    private final MessageRetries retries;
    private final ObjectMapper json;

    public PaymentEventListener(PaymentEventHandler handler, MessageRetries retries, ObjectMapper json) {
        this.handler = handler;
        this.retries = retries;
        this.json = json;
    }

    @RabbitListener(id = "payment-events", queues = PaymentEventTopology.QUEUE)
    public void onMessage(Message message) {
        try {
            PaymentEvent event = parse(message);
            handler.handle(messageId(message, event), event);
        } catch (PaymentEventException failure) {
            retries.handleFailure(message, failure, failure.retryable());
        } catch (RuntimeException failure) {
            // Beklenmedik hata (ör. veritabanına geçici olarak ulaşılamadı): tekrar denemeye değer.
            retries.handleFailure(message, failure, true);
        }
    }

    private PaymentEvent parse(Message message) {
        try {
            PaymentEvent event = json.readValue(message.getBody(), PaymentEvent.class);
            if (event.data() == null || event.data().orderId() == null || event.data().attemptId() == null
                    || event.data().status() == null) {
                throw PaymentEventException.deadLetter("Olayda zorunlu alanlar eksik.");
            }
            return event;
        } catch (JacksonException unreadable) {
            // Okunamayan mesaj beklemekle düzelmez.
            throw PaymentEventException.deadLetter("Mesaj okunamadı: " + unreadable.getOriginalMessage());
        }
    }

    /** Asıl numara AMQP messageId özelliğidir; yoksa gövdedekine bakılır. */
    private static String messageId(Message message, PaymentEvent event) {
        String fromProperties = message.getMessageProperties().getMessageId();
        if (fromProperties != null && !fromProperties.isBlank()) {
            return fromProperties;
        }
        if (event.messageId() != null && !event.messageId().isBlank()) {
            return event.messageId();
        }
        throw PaymentEventException.deadLetter("Mesajın numarası (messageId) yok; tekrarları ayıklanamaz.");
    }
}
