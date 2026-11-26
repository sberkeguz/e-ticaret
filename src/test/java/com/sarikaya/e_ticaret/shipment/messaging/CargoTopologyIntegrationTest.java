package com.sarikaya.e_ticaret.shipment.messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.sarikaya.e_ticaret.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Backend'in kargo tanımları cargo-service'inkiyle (services/cargo-service/.../Messaging/CargoTopology.cs)
 * birebir aynıdır: cargo-service'in yaptığı tanımlar backend'inkinden sonra gelse de RabbitMQ
 * PRECONDITION_FAILED vermez. Aşağıdaki argümanlar CargoTopology.DeclareAsync'ten kopyalanmıştır.
 */
@IntegrationTest
class CargoTopologyIntegrationTest {
    @Autowired private RabbitTemplate rabbit;
    @Autowired private RabbitAdmin admin;

    @BeforeEach
    void declareBackendTopology() {
        admin.initialize();
    }

    @Test
    void cargoServiceDeclarationsAfterBackendDoNotConflict() {
        rabbit.execute(channel -> {
            // Önce backend'in tanımladığını doğrula (yoksa passive declare kanalı kapatır).
            channel.exchangeDeclarePassive("cargo");
            channel.exchangeDeclarePassive("cargo.unrouted");
            channel.queueDeclarePassive("cargo.unrouted");
            channel.queueDeclarePassive("cargo.shipment-requests");
            channel.queueDeclarePassive("cargo.shipment-requests.dlq");

            declareLikeCargoService(channel);
            return null;
        });
    }

    @Test
    void shipmentCommandIsRoutedToCargoRequestQueue() {
        rabbit.execute(channel -> {
            channel.queuePurge("cargo.shipment-requests");
            channel.basicPublish("cargo", "commands.shipment.create", null, "{}".getBytes(StandardCharsets.UTF_8));
            GetResponse got = null;
            for (int i = 0; i < 50 && got == null; i++) {
                got = channel.basicGet("cargo.shipment-requests", true);
                if (got == null) {
                    sleep();
                }
            }
            assertNotNull(got, "komut cargo.shipment-requests kuyruğuna düşmeli");
            return null;
        });
    }

    @Test
    void shipmentEventsReachBackendQueueAndRetryQueuesExist() {
        rabbit.execute(channel -> {
            channel.queuePurge("backend.shipment-events");
            channel.basicPublish("cargo", "shipment.created", null, "{}".getBytes(StandardCharsets.UTF_8));
            GetResponse got = null;
            for (int i = 0; i < 50 && got == null; i++) {
                got = channel.basicGet("backend.shipment-events", true);
                if (got == null) {
                    sleep();
                }
            }
            assertNotNull(got, "olay backend.shipment-events kuyruğuna düşmeli");

            channel.queueDeclarePassive("backend.shipment-events.dlq");
            for (int attempt = 1; attempt <= 3; attempt++) {
                channel.queueDeclarePassive("backend.shipment-events.retry." + attempt);
            }
            // Komut, olay kuyruğuna düşmez (backend kendi komutunu geri almaz).
            channel.basicPublish("cargo", "commands.shipment.create", null, "{}".getBytes(StandardCharsets.UTF_8));
            sleep();
            assertEquals(0, channel.queueDeclarePassive("backend.shipment-events").getMessageCount());
            channel.queuePurge("cargo.shipment-requests");
            return null;
        });
    }

    /** CargoTopology.DeclareAsync'in birebir Java karşılığı. */
    private static void declareLikeCargoService(Channel channel) throws java.io.IOException {
        channel.exchangeDeclare("cargo.unrouted", "fanout", true, false, null);
        channel.queueDeclare("cargo.unrouted", true, false, false, null);
        channel.queueBind("cargo.unrouted", "cargo.unrouted", "");

        channel.exchangeDeclare("cargo", "topic", true, false, Map.of("alternate-exchange", "cargo.unrouted"));

        channel.queueDeclare("cargo.shipment-requests.dlq", true, false, false, null);
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("x-dead-letter-exchange", "");
        arguments.put("x-dead-letter-routing-key", "cargo.shipment-requests.dlq");
        channel.queueDeclare("cargo.shipment-requests", true, false, false, arguments);
        channel.queueBind("cargo.shipment-requests", "cargo", "commands.shipment.create");
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
