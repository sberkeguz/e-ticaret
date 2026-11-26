package com.sarikaya.e_ticaret.payment.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backend'in outbox'ı: mesajı iş kaydıyla aynı veritabanı işleminde yazar, sonra RabbitMQ'ya iletir.
 * İletim, RabbitMQ'nun "aldım" onayı (publisher confirm) gelince tamam sayılır; onay gelmezse mesaj
 * outbox'ta kalır ve tekrar denenir (en az bir kez teslim; alıcı tekrar gelen mesajı ayıklar).
 *
 * Tur iki şekilde başlar: mesaj yazan işlem kaydedilince hemen, yedek olarak da yarım saniyede bir.
 *
 * Kart verisi: 2D ödeme komutunun (ChargeRequested) payload'ı request.card alanında kartı şifresiz
 * içerir. Bu alan, mesaj RabbitMQ'ya iletilip yayın onayı gelene kadar outbox satırında durur; onay
 * gelince publishBatch aynı işlemde publishedAt'i yazar ve {@link #withoutCard} ile kartı satırdan
 * siler. Onay gelmezse kart satırda kalır ve mesaj tekrar denenir. Satırın kendisi gönderildikten
 * KEEP_PUBLISHED (7 gün) sonra deleteOldPublished ile silinir. Payload hiçbir zaman loglanmaz.
 */
@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 50;
    private static final Duration KEEP_PUBLISHED = Duration.ofDays(7);

    private final OutboxMessageRepository outbox;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExecutorService wakeups = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final AtomicBoolean running = new AtomicBoolean();

    public OutboxRelay(OutboxMessageRepository outbox, RabbitTemplate rabbit, TransactionTemplate transactions) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.transactions = transactions;
    }

    /**
     * Mesajı çağıranın işleminde kaydeder; işlem kaydedilince hemen gönderilir. payload olduğu gibi
     * JSON'a yazılır: 2D ödeme komutunda kart şifresiz olarak satıra girer ve yayın onayından sonra
     * publishBatch içinde withoutCard ile silinir.
     */
    public void enqueue(String exchange, String routingKey, String type, Object payload) {
        OutboxMessage message = new OutboxMessage();
        message.setId(UUID.randomUUID());
        message.setExchange(exchange);
        message.setRoutingKey(routingKey);
        message.setType(type);
        message.setPayload(json.writeValueAsString(payload));
        message.setCreatedAt(Instant.now());
        outbox.save(message);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wakeups.execute(OutboxRelay.this::publishPending);
                }
            });
        }
    }

    @Scheduled(fixedDelayString = "${payment.outbox-interval-ms:500}")
    public void publishPending() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Integer sent;
            do {
                sent = transactions.execute(status -> publishBatch());
            } while (sent != null && sent == BATCH);
        } catch (RuntimeException failure) {
            log.warn("Outbox gönderilemedi, sonra tekrar denenecek: {}", failure.getMessage());
        } finally {
            running.set(false);
        }
    }

    private int publishBatch() {
        List<OutboxMessage> batch = outbox.lockUnpublished(PageRequest.of(0, BATCH));
        for (OutboxMessage message : batch) {
            message.setAttempts(message.getAttempts() + 1);
            try {
                rabbit.invoke(operations -> {
                    operations.send(message.getExchange(), message.getRoutingKey(), toAmqp(message));
                    operations.waitForConfirmsOrDie(5_000);
                    return null;
                });
            } catch (RuntimeException failure) {
                // Bu ve sonraki mesajlar outbox'ta kalır (sıra korunur); bir sonraki turda tekrar denenir.
                String error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                message.setLastError(error.length() <= 500 ? error : error.substring(0, 500));
                return 0;
            }
            message.setPublishedAt(Instant.now());
            message.setPayload(withoutCard(message.getPayload()));
        }
        if (!batch.isEmpty()) {
            log.info("{} mesaj RabbitMQ'ya gönderildi", batch.size());
        }
        return batch.size();
    }

    private static Message toAmqp(OutboxMessage message) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(message.getId().toString());
        properties.setType(message.getType());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT); // RabbitMQ yeniden başlasa da kaybolmaz
        properties.setTimestamp(java.util.Date.from(message.getCreatedAt()));
        return new Message(message.getPayload().getBytes(StandardCharsets.UTF_8), properties);
    }

    /** İletilmiş mesajda kart bilgisi tutulmaz: request.card alanı silinir. */
    String withoutCard(String payload) {
        JsonNode root = json.readTree(payload);
        if (root.get("request") instanceof ObjectNode request && request.has("card")) {
            request.remove("card");
            return json.writeValueAsString(root);
        }
        return payload;
    }

    /** Gönderilmiş mesajlar bir hafta sonra silinir. */
    @Scheduled(cron = "0 45 3 * * *")
    public void deleteOldPublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(Instant.now().minus(KEEP_PUBLISHED)));
        if (deleted != null && deleted > 0) {
            log.info("{} eski outbox mesajı silindi", deleted);
        }
    }
}
