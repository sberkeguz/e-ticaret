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
            channel.exchangeDeclarePassive("cargo.dlx");
            channel.exchangeDeclarePassive("cargo.retry");

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

    /**
     * cargo-service varsayılan exchange'e ("") yazamaz: reddedilen komut adlı DLX (cargo.dlx) üzerinden DLQ'ya,
     * tanınmayan hatadaki yeniden yayın adlı retry exchange'i (cargo.retry) üzerinden komut kuyruğuna gider.
     */
    @Test
    void rejectedCommandGoesToDlqViaNamedDlxAndRetryExchangeRoutesToRequestQueue() {
        rabbit.execute(channel -> {
            channel.queuePurge("cargo.shipment-requests");
            channel.queuePurge("cargo.shipment-requests.dlq");

            channel.basicPublish("cargo.retry", "cargo.shipment-requests", null, "{}".getBytes(StandardCharsets.UTF_8));
            GetResponse retried = poll(channel, "cargo.shipment-requests");
            assertNotNull(retried, "cargo.retry'ye yayınlanan kopya komut kuyruğuna düşmeli");

            channel.basicReject(retried.getEnvelope().getDeliveryTag(), false);
            GetResponse dead = poll(channel, "cargo.shipment-requests.dlq");
            assertNotNull(dead, "reddedilen komut cargo.dlx üzerinden DLQ'ya düşmeli");
            assertEquals("cargo.shipment-requests", dead.getProps().getHeaders().get("x-first-death-queue").toString());
            assertEquals("rejected", dead.getProps().getHeaders().get("x-first-death-reason").toString());
            channel.basicAck(dead.getEnvelope().getDeliveryTag(), false);
            return null;
        });
    }

    private static GetResponse poll(Channel channel, String queue) throws java.io.IOException {
        GetResponse got = null;
        for (int i = 0; i < 50 && got == null; i++) {
            got = channel.basicGet(queue, false);
            if (got == null) {
                sleep();
            }
        }
        return got;
    }

    /** CargoTopology.DeclareAsync'in birebir Java karşılığı. */
    private static void declareLikeCargoService(Channel channel) throws java.io.IOException {
        channel.exchangeDeclare("cargo.unrouted", "fanout", true, false, null);
        channel.queueDeclare("cargo.unrouted", true, false, false, null);
        channel.queueBind("cargo.unrouted", "cargo.unrouted", "");

        channel.exchangeDeclare("cargo", "topic", true, false, Map.of("alternate-exchange", "cargo.unrouted"));
        channel.exchangeDeclare("cargo.dlx", "direct", true, false, null);
        channel.exchangeDeclare("cargo.retry", "direct", true, false, null);

        channel.queueDeclare("cargo.shipment-requests.dlq", true, false, false, null);
        channel.queueBind("cargo.shipment-requests.dlq", "cargo.dlx", "cargo.shipment-requests.dlq");
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("x-dead-letter-exchange", "cargo.dlx");
        arguments.put("x-dead-letter-routing-key", "cargo.shipment-requests.dlq");
        channel.queueDeclare("cargo.shipment-requests", true, false, false, arguments);
        channel.queueBind("cargo.shipment-requests", "cargo", "commands.shipment.create");
        channel.queueBind("cargo.shipment-requests", "cargo.retry", "cargo.shipment-requests");
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
