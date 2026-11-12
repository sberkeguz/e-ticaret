package com.sarikaya.e_ticaret.payment.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** İşlenen mesaj numaraları. Aynı mesaj iki kez gelirse ikincisi atlanır. */
@Component
public class ProcessedMessages {
    private final JdbcTemplate jdbc;

    public ProcessedMessages(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Mesajı "işlendi" diye işaretler. Daha önce işlendiyse false döner.
     * Çağıranın veritabanı işlemi içinde çalışır: iş geri alınırsa işaret de geri alınır.
     */
    public boolean markProcessed(String messageId, String messageType) {
        int inserted = jdbc.update("""
                INSERT INTO processed_messages (message_id, message_type) VALUES (?, ?)
                ON CONFLICT (message_id) DO NOTHING""", messageId, messageType);
        return inserted == 1;
    }

    /**
     * Tablo sonsuza kadar büyümesin diye eski kayıtları siler. RabbitMQ bir mesajı günler sonra
     * tekrar göndermez; DLQ'dan elle tekrar işlenen mesajı da işleyici zaten durum kontrolleriyle
     * zararsız kılar (aynı fazla ödeme için tek uyarı / tek iade).
     */
    public int deleteOlderThan(Duration age) {
        return jdbc.update("DELETE FROM processed_messages WHERE processed_at < now() - make_interval(secs => ?)",
                age.toSeconds());
    }
}
