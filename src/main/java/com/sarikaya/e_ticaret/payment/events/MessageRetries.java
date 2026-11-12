package com.sarikaya.e_ticaret.payment.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * İşlenemeyen mesajın gideceği yer: bekleme kuyruğu (5 sn → 30 sn → 2 dk) ya da DLQ.
 * Mesaj, RabbitMQ yeni yerini onaylamadan bırakılmaz; onay gelmezse hata fırlar ve
 * ana kuyruğun kendi DLQ ayarı devreye girer. Hiçbir mesaj sessizce kaybolmaz.
 */
@Component
public class MessageRetries {
    private static final Logger log = LoggerFactory.getLogger(MessageRetries.class);
    static final String RETRY_COUNT = "x-retry-count";
    static final String ERROR = "x-error";
    private static final long CONFIRM_TIMEOUT_MS = 10_000;

    private final RabbitTemplate rabbit;

    public MessageRetries(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public void handleFailure(Message message, Exception failure, boolean retryable) {
        MessageProperties properties = message.getMessageProperties();
        int retriesSoFar = retriesSoFar(properties);
        properties.setHeader(ERROR, String.valueOf(failure.getMessage()));

        boolean canRetry = retryable && retriesSoFar < PaymentEventTopology.RETRY_DELAYS.size();
        if (canRetry) {
            int next = retriesSoFar + 1;
            Duration delay = PaymentEventTopology.RETRY_DELAYS.get(retriesSoFar);
            properties.setHeader(RETRY_COUNT, next);
            send(PaymentEventTopology.retryQueue(next), message);
            log.info("Mesaj {} {} sonra tekrar denenecek ({}. tekrar): {}",
                    properties.getMessageId(), delay, next, failure.getMessage());
        } else {
            properties.setHeader("x-failed-at", Instant.now().toString());
            send(PaymentEventTopology.DEAD_LETTER_QUEUE, message);
            log.error("Mesaj {} DLQ'ya taşındı ({} tekrar sonra): {}",
                    properties.getMessageId(), retriesSoFar, failure.getMessage());
        }
    }

    private void send(String queue, Message message) {
        rabbit.invoke(operations -> {
            operations.send("", queue, message);
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
    }

    private static int retriesSoFar(MessageProperties properties) {
        Object value = properties.getHeaders().get(RETRY_COUNT);
        return value instanceof Number number ? number.intValue() : 0;
    }
}
