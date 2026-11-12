package com.sarikaya.e_ticaret.payment.events;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DLQ'daki (işlenemeyen) mesajlar için admin araçları: listele, tekrar işle, sil.
 * Mesajlar listelenirken kuyruktan alınıp hemen geri bırakılır; hiçbiri kaybolmaz.
 */
@Component
public class DeadLetters {
    private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);
    private static final int MAX_LIST = 50;

    private final RabbitTemplate rabbit;
    private final ObjectMapper json;

    public DeadLetters(RabbitTemplate rabbit, ObjectMapper json) {
        this.rabbit = rabbit;
        this.json = json;
    }

    public record DeadLetter(String messageId, String type, Long orderId, String status, String amount,
                             String error, String failedAt, int retries) {
    }

    public long count() {
        Long count = rabbit.execute(channel -> (long) channel.queueDeclarePassive(PaymentEventTopology.DEAD_LETTER_QUEUE).getMessageCount());
        return count == null ? 0 : count;
    }

    public List<DeadLetter> list() {
        List<DeadLetter> result = rabbit.execute(channel -> {
            List<DeadLetter> found = new ArrayList<>();
            List<Long> tags = new ArrayList<>();
            try {
                for (int i = 0; i < MAX_LIST; i++) {
                    GetResponse message = channel.basicGet(PaymentEventTopology.DEAD_LETTER_QUEUE, false);
                    if (message == null) {
                        break;
                    }
                    tags.add(message.getEnvelope().getDeliveryTag());
                    found.add(describe(message));
                }
            } finally {
                requeue(channel, tags);
            }
            return found;
        });
        return result == null ? List.of() : result;
    }

    /** Mesajı ana kuyruğa geri koyar; tekrar sayacı sıfırlanır. Bulunamazsa false. */
    public boolean replay(String messageId) {
        return Boolean.TRUE.equals(take(messageId, (channel, message) -> {
            AMQP.BasicProperties props = message.getProps();
            Map<String, Object> headers = new HashMap<>(props.getHeaders() == null ? Map.of() : props.getHeaders());
            headers.remove(MessageRetries.RETRY_COUNT);
            headers.remove(MessageRetries.ERROR);
            headers.remove("x-failed-at");
            headers.remove("x-death");
            headers.put("x-replayed", true);
            channel.confirmSelect();
            channel.basicPublish("", PaymentEventTopology.QUEUE, props.builder().headers(headers).build(), message.getBody());
            channel.waitForConfirmsOrDie(10_000);
            log.info("DLQ mesajı {} admin tarafından tekrar işlemeye gönderildi", messageId);
        }));
    }

    /** Mesajı kalıcı olarak siler (admin "önemsiz" dedi). Bulunamazsa false. */
    public boolean discard(String messageId) {
        return Boolean.TRUE.equals(take(messageId, (channel, message) ->
                log.warn("DLQ mesajı {} admin tarafından silindi", messageId)));
    }

    private interface MessageAction {
        void apply(Channel channel, GetResponse message) throws Exception;
    }

    /** DLQ'da mesajı arar; bulursa işlemi yapıp onaylar (kuyruktan çıkar), diğerlerini geri bırakır. */
    private Boolean take(String messageId, MessageAction action) {
        return rabbit.execute(channel -> {
            List<Long> others = new ArrayList<>();
            try {
                for (int i = 0; i < 10_000; i++) {
                    GetResponse message = channel.basicGet(PaymentEventTopology.DEAD_LETTER_QUEUE, false);
                    if (message == null) {
                        return false;
                    }
                    if (messageId.equals(message.getProps().getMessageId())) {
                        action.apply(channel, message);
                        channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
                        return true;
                    }
                    others.add(message.getEnvelope().getDeliveryTag());
                }
                return false;
            } finally {
                requeue(channel, others);
            }
        });
    }

    private static void requeue(Channel channel, List<Long> tags) throws java.io.IOException {
        for (Long tag : tags) {
            channel.basicNack(tag, false, true);
        }
    }

    private DeadLetter describe(GetResponse message) {
        AMQP.BasicProperties props = message.getProps();
        Map<String, Object> headers = props.getHeaders() == null ? Map.of() : props.getHeaders();
        Optional<JsonNode> data = Optional.empty();
        try {
            data = Optional.ofNullable(json.readTree(message.getBody()).get("data"));
        } catch (RuntimeException unreadable) {
            // okunamayan mesaj da listelenir
        }
        Object retries = headers.get(MessageRetries.RETRY_COUNT);
        return new DeadLetter(
                props.getMessageId(),
                props.getType(),
                data.map(d -> d.path("orderId").isMissingNode() ? null : d.path("orderId").asLong()).orElse(null),
                data.map(d -> d.path("status").asString(null)).orElse(null),
                data.map(d -> d.path("amount").asString(null)).orElse(null),
                text(headers.get(MessageRetries.ERROR)),
                text(headers.get("x-failed-at")),
                retries instanceof Number n ? n.intValue() : 0);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
    }
}
