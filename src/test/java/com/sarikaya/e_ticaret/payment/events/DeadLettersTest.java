package com.sarikaya.e_ticaret.payment.events;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gerçek RabbitMQ'da: DLQ mesajı listelenir, tekrar işlenir veya silinir; diğerleri kaybolmaz. */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class DeadLettersTest {
    @Autowired private DeadLetters deadLetters;
    @Autowired private RabbitTemplate rabbit;

    @Test
    void listReplayAndDiscard() {
        String replayId = "test-dlq-" + UUID.randomUUID();
        String discardId = "test-dlq-" + UUID.randomUUID();
        putInDeadLetters(replayId);
        putInDeadLetters(discardId);

        var listed = deadLetters.list().stream().filter(m -> m.messageId().startsWith("test-dlq-")).toList();
        assertTrue(listed.stream().anyMatch(m -> m.messageId().equals(replayId) && m.orderId() == 999_123L
                && "işlenemedi".equals(m.error()) && m.retries() == 3));
        assertTrue(deadLetters.count() >= 2); // sayı RabbitMQ'da gecikmeli güncellenebilir; kesin kontrol aşağıda

        assertTrue(deadLetters.discard(discardId));
        assertTrue(deadLetters.replay(replayId));

        assertFalse(deadLetters.list().stream().anyMatch(m -> m.messageId().equals(replayId) || m.messageId().equals(discardId)));
        assertFalse(deadLetters.discard("olmayan-mesaj"));
    }

    private void putInDeadLetters(String messageId) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(messageId);
        props.setType("PaymentFailed");
        props.setHeader(MessageRetries.RETRY_COUNT, 3);
        props.setHeader(MessageRetries.ERROR, "işlenemedi");
        String body = """
                {"type":"PaymentFailed","version":1,"data":{"attemptId":"%s","orderId":999123,"status":"FAILED","amount":"1.00","threeDS":false}}"""
                .formatted(UUID.randomUUID());
        rabbit.send("", PaymentEventTopology.DEAD_LETTER_QUEUE, new Message(body.getBytes(StandardCharsets.UTF_8), props));
    }
}
