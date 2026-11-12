package com.sarikaya.e_ticaret.payment.events;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Gerçek veritabanında: 14 günden eski kayıt silinir, yeni kayıt kalır. */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class ProcessedMessagesCleanupTest {
    @Autowired private ProcessedMessagesCleanup cleanup;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void deletesOnlyOldRecords() {
        String old = "test-old-" + UUID.randomUUID();
        String fresh = "test-fresh-" + UUID.randomUUID();
        jdbc.update("INSERT INTO processed_messages (message_id, message_type, processed_at) VALUES (?, 'Test', now() - interval '15 days')", old);
        jdbc.update("INSERT INTO processed_messages (message_id, message_type, processed_at) VALUES (?, 'Test', now() - interval '1 day')", fresh);
        try {
            cleanup.cleanUp();

            assertEquals(0, count(old));
            assertEquals(1, count(fresh));
        } finally {
            jdbc.update("DELETE FROM processed_messages WHERE message_id IN (?, ?)", old, fresh);
        }
    }

    private int count(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_messages WHERE message_id = ?", Integer.class, id);
    }
}
