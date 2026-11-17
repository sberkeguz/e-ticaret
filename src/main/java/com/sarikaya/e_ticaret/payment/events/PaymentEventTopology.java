package com.sarikaya.e_ticaret.payment.events;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class PaymentEventTopology {
    public static final String EXCHANGE = "payments";
    public static final String UNROUTED_EXCHANGE = "payments.unrouted";
    public static final String UNROUTED_QUEUE = "payments.unrouted";

    public static final String QUEUE = "backend.payment-events";
    public static final String DEAD_LETTER_QUEUE = QUEUE + ".dlq";

    /**
     * 2D ödeme isteği (komut): backend gönderir, iyzico-service işler. Olay anahtarlarından ("payment.#")
     * ayrı tutulur ki backend kendi komutunu geri almasın. Kuyruğu iki taraf da aynı ayarlarla tanımlar;
     * iyzico-service kapalıyken bile istek kuyrukta bekler.
     */
    public static final String CHARGE_ROUTING_KEY = "commands.payment.charge";
    public static final String CHARGE_QUEUE = "iyzico.charge-requests";

    /** Denemeler arası bekleme giderek uzar. Sırası önemli: i. tekrar i. kuyruktan geçer. */
    public static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2));

    public static String retryQueue(int attempt) {
        return QUEUE + ".retry." + attempt;
    }

    @Bean
    Declarables paymentEventDeclarables() {
        // IyzicoService de aynı exchange'leri aynı ayarlarla tanımlar; hangisi önce açılırsa açılsın uyumludur.
        FanoutExchange unrouted = ExchangeBuilder.fanoutExchange(UNROUTED_EXCHANGE).durable(true).build();
        Queue unroutedQueue = QueueBuilder.durable(UNROUTED_QUEUE).build();
        TopicExchange payments = ExchangeBuilder.topicExchange(EXCHANGE).durable(true).alternate(UNROUTED_EXCHANGE).build();

        // Dinleyici beklenmedik şekilde patlarsa (ör. tekrar deneme kuyruğuna da yazamazsa) mesaj DLQ'ya düşer.
        Queue main = QueueBuilder.durable(QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
        Queue deadLetters = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();

        List<Declarable> all = new ArrayList<>(List.of(
                unrouted, unroutedQueue, BindingBuilder.bind(unroutedQueue).to(unrouted),
                payments, main, deadLetters,
                BindingBuilder.bind(main).to(payments).with("payment.#")));
        Queue chargeRequests = QueueBuilder.durable(CHARGE_QUEUE).build();
        all.add(chargeRequests);
        all.add(BindingBuilder.bind(chargeRequests).to(payments).with(CHARGE_ROUTING_KEY));

        // Bekleme kuyrukları: kimse dinlemez; mesajın süresi (TTL) dolunca ana kuyruğa geri döner.
        for (int attempt = 1; attempt <= RETRY_DELAYS.size(); attempt++) {
            all.add(QueueBuilder.durable(retryQueue(attempt))
                    .ttl((int) RETRY_DELAYS.get(attempt - 1).toMillis())
                    .deadLetterExchange("")
                    .deadLetterRoutingKey(QUEUE)
                    .build());
        }
        return new Declarables(all);
    }
}
