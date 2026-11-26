package com.sarikaya.e_ticaret.shipment.messaging;

import com.sarikaya.e_ticaret.payment.events.PaymentEventTopology;
import com.sarikaya.e_ticaret.payment.events.RetryRoute;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Kargo mesajlaşması (sözleşme v1). Exchange ve komut kuyruğu tanımları cargo-service'in
 * CargoTopology.cs dosyasıyla BİREBİR aynıdır; hangisi önce açılırsa açılsın uyumludur. Argümanlardan
 * biri farklı olursa RabbitMQ ikinci tanımı PRECONDITION_FAILED ile reddeder ve iki servis de bozulur;
 * bu yüzden aşağıdaki argümanlar cargo-service'le birlikte değiştirilmeden değiştirilmez.
 *
 * Komut (backend → cargo-service): {@link #SHIPMENT_CREATE_ROUTING_KEY} → {@link #SHIPMENT_REQUESTS_QUEUE}.
 * Olaylar (cargo-service → backend): "shipment.*" → {@link #EVENTS_QUEUE} (backend'in kendi kuyruğu).
 */
@Configuration
public class CargoTopology {
    public static final String EXCHANGE = "cargo";
    public static final String UNROUTED_EXCHANGE = "cargo.unrouted";
    public static final String UNROUTED_QUEUE = "cargo.unrouted";

    public static final String SHIPMENT_CREATE_ROUTING_KEY = "commands.shipment.create";
    public static final String SHIPMENT_REQUESTS_QUEUE = "cargo.shipment-requests";
    public static final String SHIPMENT_REQUESTS_DEAD_LETTER_QUEUE = SHIPMENT_REQUESTS_QUEUE + ".dlq";

    /** Backend'in gönderi olayı kuyruğu; tekrar deneme ve DLQ adları buradan türer. */
    public static final String EVENTS_QUEUE = "backend.shipment-events";
    public static final RetryRoute EVENTS_ROUTE = new RetryRoute(EVENTS_QUEUE);
    public static final String EVENTS_DEAD_LETTER_QUEUE = EVENTS_ROUTE.deadLetterQueue();

    @Bean
    Declarables cargoDeclarables() {
        // cargo-service ile ortak tanımlar (CargoTopology.DeclareAsync).
        FanoutExchange unrouted = ExchangeBuilder.fanoutExchange(UNROUTED_EXCHANGE).durable(true).build();
        Queue unroutedQueue = QueueBuilder.durable(UNROUTED_QUEUE).build();
        TopicExchange cargo = ExchangeBuilder.topicExchange(EXCHANGE).durable(true).alternate(UNROUTED_EXCHANGE).build();
        Queue requestsDeadLetters = QueueBuilder.durable(SHIPMENT_REQUESTS_DEAD_LETTER_QUEUE).build();
        // Yalnız bu iki argüman: x-dead-letter-exchange "" ve x-dead-letter-routing-key "...dlq".
        Queue requests = QueueBuilder.durable(SHIPMENT_REQUESTS_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(SHIPMENT_REQUESTS_DEAD_LETTER_QUEUE)
                .build();

        // Backend'in olay kuyruğu: dinleyici beklenmedik şekilde patlarsa mesaj DLQ'ya düşer.
        Queue events = QueueBuilder.durable(EVENTS_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(EVENTS_DEAD_LETTER_QUEUE)
                .build();
        Queue eventDeadLetters = QueueBuilder.durable(EVENTS_DEAD_LETTER_QUEUE).build();

        List<Declarable> all = new ArrayList<>(List.of(
                unrouted, unroutedQueue, BindingBuilder.bind(unroutedQueue).to(unrouted),
                cargo, requestsDeadLetters, requests,
                BindingBuilder.bind(requests).to(cargo).with(SHIPMENT_CREATE_ROUTING_KEY),
                events, eventDeadLetters,
                BindingBuilder.bind(events).to(cargo).with("shipment.#")));

        // Bekleme kuyrukları: kimse dinlemez; süre (TTL) dolunca mesaj olay kuyruğuna geri döner.
        for (int attempt = 1; attempt <= PaymentEventTopology.RETRY_DELAYS.size(); attempt++) {
            all.add(QueueBuilder.durable(EVENTS_ROUTE.retryQueue(attempt))
                    .ttl((int) PaymentEventTopology.RETRY_DELAYS.get(attempt - 1).toMillis())
                    .deadLetterExchange("")
                    .deadLetterRoutingKey(EVENTS_QUEUE)
                    .build());
        }
        return new Declarables(all);
    }
}
