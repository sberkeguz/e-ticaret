package com.sarikaya.e_ticaret.payment.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
}
