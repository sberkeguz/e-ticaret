package com.sarikaya.e_ticaret.payment.events;

import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin paneli: işlenemeyen (DLQ'ya düşen) ödeme mesajları. (/api/admin/** yalnızca ADMIN) */
@RestController
@RequestMapping("/api/admin/dead-letters")
public class AdminDeadLetterController {
    private final DeadLetters deadLetters;

    public AdminDeadLetterController(DeadLetters deadLetters) {
        this.deadLetters = deadLetters;
    }

    public record DeadLetterList(long total, List<DeadLetters.DeadLetter> messages) {
    }

    @GetMapping
    public DeadLetterList list() {
        return new DeadLetterList(deadLetters.count(), deadLetters.list());
    }

    /** Sorun giderildiyse (ör. eksik sipariş bulundu) mesajı tekrar işlemeye gönderir. */
    @PostMapping("/{messageId}/replay")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replay(@PathVariable String messageId) {
        if (!deadLetters.replay(messageId)) {
            throw new ResourceNotFoundException("Mesaj DLQ'da bulunamadı.");
        }
    }

    /** Önemsiz olduğuna karar verilen mesajı siler. */
    @DeleteMapping("/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable String messageId) {
        if (!deadLetters.discard(messageId)) {
            throw new ResourceNotFoundException("Mesaj DLQ'da bulunamadı.");
        }
    }
}
