package com.sarikaya.e_ticaret.payment.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ProcessedMessages {
    private final JdbcTemplate jdbc;

    public ProcessedMessages(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean markProcessed(String messageId, String messageType) {
        int inserted = jdbc.update("""
                INSERT INTO processed_messages (message_id, message_type) VALUES (?, ?)
                ON CONFLICT (message_id) DO NOTHING""", messageId, messageType);
        return inserted == 1;
    }

    public int deleteOlderThan(Duration age) {
        return jdbc.update("DELETE FROM processed_messages WHERE processed_at < now() - make_interval(secs => ?)",
                age.toSeconds());
    }
}
