package com.sarikaya.e_ticaret.payment.events;

import com.sarikaya.e_ticaret.payment.alerts.ExtraPaymentRefunder;
import com.sarikaya.e_ticaret.payment.alerts.PaymentAlert;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * IyzicoService'in ödeme olayını siparişe uygular. Tek bir veritabanı işlemidir:
 * mesaj numarasını "işlendi" diye yazmak ve siparişi güncellemek birlikte olur ya da hiç olmaz.
 *
 * Olay çoğu zaman senkron cevapla aynı sonucu taşır ve hiçbir şey değiştirmez. İşe yaradığı yerler:
 * <ul>
 *   <li>Cevap kaybolduysa: para çekildiyse sipariş yine de "ödendi" olur.</li>
 *   <li>Fazladan ödeme geldiyse (ikinci ödeme, süresi dolmuş siparişe ödeme, siparişi olmayan ödeme):
 *       otomatik iade edilir ve admin'e uyarı düşer.</li>
 * </ul>
 */
@Service
public class PaymentEventHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventHandler.class);

    /**
     * Sipariş bulunamazsa bu kadar beklenir: sipariş işlemi hâlâ sürüyor olabilir.
     * Olay bundan eskiyse sipariş gerçekten yok demektir (işlem geri alınmış).
     */
    static final Duration ORPHAN_AFTER = Duration.ofMinutes(2);

    private final ProcessedMessages processed;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final ExtraPaymentRefunder refunder;
    private final Clock clock;

    @Autowired
    public PaymentEventHandler(ProcessedMessages processed, PaymentRepository payments,
                               PaymentService paymentService, ExtraPaymentRefunder refunder) {
        this(processed, payments, paymentService, refunder, Clock.systemUTC());
    }

    PaymentEventHandler(ProcessedMessages processed, PaymentRepository payments,
                        PaymentService paymentService, ExtraPaymentRefunder refunder, Clock clock) {
        this.processed = processed;
        this.payments = payments;
        this.paymentService = paymentService;
        this.refunder = refunder;
        this.clock = clock;
    }

    @Transactional
    public void handle(String messageId, PaymentEvent event) {
        if (!processed.markProcessed(messageId, event.type())) {
            log.info("Mesaj {} daha önce işlenmiş, atlandı", messageId);
            return;
        }
        PaymentEvent.Data data = event.data();
        switch (data.status()) {
            case "SUCCEEDED", "VERIFYING" -> applyMoneyTaken(event, data);
            // Para çekilmedi: senkron akış siparişi zaten doğru bıraktı (bekliyor veya geri alındı).
            case "FAILED" -> log.debug("Sipariş {} denemesi başarısız; yapılacak bir şey yok", data.orderId());
            // İadeyi admin iptali ya da bu sınıf başlatır ve siparişi o anda günceller; olay yalnızca bilgi.
            case "REFUNDED" -> log.debug("Sipariş {} iade edildi", data.orderId());
            default -> log.warn("Tanınmayan ödeme durumu '{}' (mesaj {}); görmezden gelindi", data.status(), messageId);
        }
    }

    /** Para çekildi (SUCCEEDED) veya çekilmiş olabilir (VERIFYING). */
    private void applyMoneyTaken(PaymentEvent event, PaymentEvent.Data data) {
        Optional<Payment> found = payments.findByOrderIdForUpdate(data.orderId());
        if (found.isEmpty()) {
            if (isYoungerThan(event, ORPHAN_AFTER)) {
                // Büyük ihtimalle sipariş işlemi daha bitmedi. Biraz sonra tekrar dene.
                throw PaymentEventException.retryLater("Sipariş " + data.orderId() + " henüz görünmüyor.");
            }
            refunder.refund(data, PaymentAlert.Kind.ORPHAN_PAYMENT,
                    "Sipariş " + data.orderId() + " bulunamadı ama ödeme alındı.");
            return;
        }

        Payment payment = found.get();
        boolean sameAttempt = data.attemptId().equals(payment.getAttemptId());
        if (sameAttempt && isOlderThanApplied(event, payment)) {
            log.info("Sipariş {} için eski olay (sıra {} ≤ {}) atlandı", data.orderId(), event.sequence(), payment.getLastEventSequence());
            return;
        }
        if (event.sequence() != null) {
            payment.setLastEventSequence(Math.max(event.sequence(),
                    payment.getLastEventSequence() == null ? 0 : payment.getLastEventSequence()));
        }
        switch (payment.getStatus()) {
            case PENDING -> {
                // Cevap kaybolmuş ya da asenkron ödeme: olay sayesinde sipariş tamamlanıyor.
                payment.setAttemptId(data.attemptId());
                if (data.iyzicoPaymentId() != null) {
                    payment.setToken(data.iyzicoPaymentId());
                }
                paymentService.applyAttemptResult(payment, payment.getOrder(), data.status());
                log.info("Sipariş {} ödeme olayıyla tamamlandı ({})", data.orderId(), data.status());
            }
            case PAID, REVIEW, REFUNDED -> {
                if (!sameAttempt) {
                    refunder.refund(data, PaymentAlert.Kind.SECOND_PAYMENT,
                            "Sipariş " + data.orderId() + " zaten ödenmişken ikinci bir ödeme alındı.");
                    return;
                }
                // İnceleme sonucu netleşti: para alınmış.
                if (payment.getStatus() == PaymentStatus.REVIEW && "SUCCEEDED".equals(data.status())) {
                    paymentService.applyAttemptResult(payment, payment.getOrder(), "SUCCEEDED");
                    log.info("Sipariş {} incelemeden çıktı, ödendi", data.orderId());
                }
            }
            // EXPIRED, CANCELLED, FAILED: sipariş artık yok sayılır; stok çoktan bırakıldı.
            default -> refunder.refund(data, PaymentAlert.Kind.LATE_PAYMENT,
                    "Sipariş " + data.orderId() + " " + payment.getStatus() + " durumundayken ödeme geldi.");
        }
    }

    /** Olayların sırası karışırsa (paralel dinleyiciler) eskisi yenisinin üzerine yazılmasın. */
    private static boolean isOlderThanApplied(PaymentEvent event, Payment payment) {
        return event.sequence() != null && payment.getLastEventSequence() != null
                && event.sequence() <= payment.getLastEventSequence();
    }

    private boolean isYoungerThan(PaymentEvent event, Duration age) {
        try {
            Instant occurredAt = Instant.parse(event.occurredAt());
            return occurredAt.isAfter(clock.instant().minus(age));
        } catch (DateTimeParseException | NullPointerException unknownTime) {
            return false;
        }
    }
}
