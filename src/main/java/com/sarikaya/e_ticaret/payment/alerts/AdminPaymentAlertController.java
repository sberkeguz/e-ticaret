package com.sarikaya.e_ticaret.payment.alerts;

import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Admin paneli: fazladan alınan ödemeler ve otomatik iadeleri. (/api/admin/** yalnızca ADMIN) */
@RestController
@RequestMapping("/api/admin/payment-alerts")
public class AdminPaymentAlertController {
    private final PaymentAlertRepository alerts;

    public AdminPaymentAlertController(PaymentAlertRepository alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public List<AlertResponse> list(@RequestParam(defaultValue = "true") boolean openOnly) {
        var rows = openOnly
                ? alerts.findTop50ByResolvedAtIsNullOrderByCreatedAtDesc()
                : alerts.findTop50ByOrderByCreatedAtDesc();
        return rows.stream().map(AlertResponse::from).toList();
    }

    /** Admin uyarıyı gördü, ilgilendi. */
    @PostMapping("/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void resolve(@PathVariable Long id) {
        PaymentAlert alert = alerts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Uyarı bulunamadı."));
        if (alert.getResolvedAt() == null) {
            alert.setResolvedAt(Instant.now());
        }
    }

    public record AlertResponse(Long id, String kind, Long orderId, UUID attemptId, String iyzicoPaymentId,
                                BigDecimal amount, boolean refunded, String message, Instant createdAt,
                                Instant resolvedAt) {
        static AlertResponse from(PaymentAlert alert) {
            return new AlertResponse(alert.getId(), alert.getKind().name(), alert.getOrderId(), alert.getAttemptId(),
                    alert.getIyzicoPaymentId(), alert.getAmount(), alert.isRefunded(), alert.getMessage(),
                    alert.getCreatedAt(), alert.getResolvedAt());
        }
    }
}
