package com.sarikaya.e_ticaret.payment.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Her gece 03:15'te, processedRetentionDays günden eski "işlendi" kayıtlarını siler. */
@Component
public class ProcessedMessagesCleanup {
    private static final Logger log = LoggerFactory.getLogger(ProcessedMessagesCleanup.class);

    private final ProcessedMessages processed;
    private final Duration retention;

    public ProcessedMessagesCleanup(ProcessedMessages processed,
                                    @Value("${messaging.processed-retention-days:14}") int retentionDays) {
        this.processed = processed;
        this.retention = Duration.ofDays(retentionDays);
    }

    @Scheduled(cron = "${messaging.processed-cleanup-cron:0 15 3 * * *}")
    public int cleanUp() {
        int deleted = processed.deleteOlderThan(retention);
        if (deleted > 0) {
            log.info("{} eski işlenmiş mesaj kaydı silindi ({} günden eski)", deleted, retention.toDays());
        }
        return deleted;
    }
}
