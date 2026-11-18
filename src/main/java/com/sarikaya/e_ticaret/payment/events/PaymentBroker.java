package com.sarikaya.e_ticaret.payment.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * RabbitMQ'ya ulaşılabiliyor mu? 2D ödeme yalnızca RabbitMQ ile gider; RabbitMQ kapalıyken ödeme isteği
 * outbox'ta bekler ve müşteriye "ödemeler geçici olarak alınamıyor" denir. Birkaç saniyede bir bağlantı
 * denenir; cevap gecikirse (ağ asılı kaldıysa) kapalı sayılır.
 */
@Component
public class PaymentBroker {
    private static final Logger log = LoggerFactory.getLogger(PaymentBroker.class);
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(3);

    private final ConnectionFactory connections;
    private final Clock clock;
    private final ExecutorService checker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private volatile CompletableFuture<Boolean> running;
    /** null: çalışıyor. Dolu: o andan beri ulaşılamıyor. Açılışta henüz bilinmediği için çalışıyor sayılır. */
    private volatile Instant downSince;

    @Autowired
    public PaymentBroker(ConnectionFactory connections) {
        this(connections, Clock.systemUTC());
    }

    PaymentBroker(ConnectionFactory connections, Clock clock) {
        this.connections = connections;
        this.clock = clock;
    }

    public boolean isUp() {
        return downSince == null;
    }

    public Instant downSince() {
        return downSince;
    }

    @Scheduled(fixedDelayString = "${payment.broker-check-ms:5000}")
    public void check() {
        if (running == null || running.isDone()) {
            running = CompletableFuture.supplyAsync(this::canConnect, checker);
        }
        boolean up;
        try {
            up = running.get(CHECK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception slowOrFailed) {
            up = false; // asılı kalan deneme arka planda bitince bir sonraki kontrol yenisini başlatır
        }
        record(up);
    }

    void record(boolean up) {
        if (up && downSince != null) {
            log.info("RabbitMQ'ya yeniden ulaşıldı ({} sn kapalıydı); 2D ödemeler devam ediyor",
                    Duration.between(downSince, clock.instant()).toSeconds());
            downSince = null;
        } else if (!up && downSince == null) {
            downSince = clock.instant();
            log.error("RabbitMQ'ya ulaşılamıyor: 2D ödemeler alınamıyor, istekler outbox'ta bekliyor");
        }
    }

    private boolean canConnect() {
        try (Connection connection = connections.createConnection()) {
            return connection.isOpen();
        } catch (RuntimeException unreachable) {
            return false;
        }
    }
}
