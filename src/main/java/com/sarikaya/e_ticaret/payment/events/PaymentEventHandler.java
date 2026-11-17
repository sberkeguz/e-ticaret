package com.sarikaya.e_ticaret.payment.events;

import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.payment.alerts.ExtraPaymentAlerts;
import com.sarikaya.e_ticaret.payment.alerts.PaymentAlert;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.payment.service.PaymentResultWaiters;
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

@Service
public class PaymentEventHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventHandler.class);

    static final Duration ORPHAN_AFTER = Duration.ofMinutes(2);

    private final PaymentResultWaiters waiters;
    private final ProcessedMessages processed;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final ExtraPaymentAlerts extraPayments;
    private final Clock clock;

    @Autowired
    public PaymentEventHandler(ProcessedMessages processed, PaymentRepository payments,
                               PaymentService paymentService, ExtraPaymentAlerts extraPayments,
                               PaymentResultWaiters waiters) {
        this(processed, payments, paymentService, extraPayments, waiters, Clock.systemUTC());
    }

    PaymentEventHandler(ProcessedMessages processed, PaymentRepository payments,
                        PaymentService paymentService, ExtraPaymentAlerts extraPayments,
                        PaymentResultWaiters waiters, Clock clock) {
        this.waiters = waiters;
        this.processed = processed;
        this.payments = payments;
        this.paymentService = paymentService;
        this.extraPayments = extraPayments;
        this.clock = clock;
    }

    @Transactional
    public void handle(String messageId, PaymentEvent event) {
        if (!processed.markProcessed(messageId, event.type())) {
            log.info("Mesaj {} daha önce işlenmiş, atlandı", messageId);
            return;
        }
        PaymentEvent.Data data = event.data();
        // "Ödemeniz işleniyor..." ekranında bekleyen varsa, bu işlem kaydedilince cevabını alır.
        waiters.wakeAfterCommit(data.orderId());
        switch (data.status()) {
            case "SUCCEEDED", "VERIFYING" -> applyMoneyTaken(event, data);
            // Para çekilmedi: senkron akış siparişi zaten doğru bıraktı (bekliyor veya geri alındı).
            case "FAILED" -> recordFailure(event, data);
            // İadeyi admin iptali ya da bu sınıf başlatır ve siparişi o anda günceller; olay yalnızca bilgi.
            case "REFUNDED" -> log.debug("Sipariş {} iade edildi", data.orderId());
            default -> log.warn("Tanınmayan ödeme durumu '{}' (mesaj {}); görmezden gelindi", data.status(), messageId);
        }
    }

    /**
     * Para çekilmedi.
     *  - Kuyrukla giden 2D isteğiyse sebep kaydedilir (ekran "reddedildi: sebep" gösterir); sipariş
     *    beklemede kalır, kullanıcı başka kartla tekrar deneyebilir.
     *  - Ödeme incelemedeyse (sonucu belirsizdi, şimdi "çekilmedi" diye netleşti) sipariş incelemeden
     *    çıkar ve beklemeye döner: kullanıcı tekrar ödeyebilir ya da süre dolunca stok bırakılır.
     *    Aksi hâlde sipariş sonsuza kadar incelemede kalırdı.
     */
    private void recordFailure(PaymentEvent event, PaymentEvent.Data data) {
        payments.findByOrderIdForUpdate(data.orderId())
                .filter(payment -> data.attemptId().equals(payment.getAttemptId()))
                .ifPresent(payment -> {
                    if (isOlderThanApplied(event, payment)) {
                        log.info("Sipariş {} için eski olay (sıra {} ≤ {}) atlandı", data.orderId(), event.sequence(), payment.getLastEventSequence());
                        return;
                    }
                    if (event.sequence() != null) {
                        payment.setLastEventSequence(Math.max(event.sequence(),
                                payment.getLastEventSequence() == null ? 0 : payment.getLastEventSequence()));
                    }
                    if (payment.getLastAttemptStatus() != null) {
                        payment.setLastAttemptStatus("FAILED");
                        payment.setFailureReason(data.failureReason());
                    }
                    if (payment.getStatus() == PaymentStatus.REVIEW) {
                        payment.setStatus(PaymentStatus.PENDING);
                        payment.getOrder().setStatus(OrderStatus.PAYMENT_PENDING);
                        payment.setFailureReason(data.failureReason());
                        log.info("Sipariş {} incelemeden çıktı: para çekilmedi ({})", data.orderId(), data.failureReason());
                    } else {
                        log.info("Sipariş {} ödemesi başarısız: {}", data.orderId(), data.failureReason());
                    }
                    if (payment.getStatus() == PaymentStatus.PENDING) {
                        // Ret ne kadar geç gelirse gelsin müşteriye tekrar denemek için tam süre verilir.
                        payment.setReservationStartedAt(clock.instant());
                    }
                });
    }

    /** Para çekildi (SUCCEEDED) veya çekilmiş olabilir (VERIFYING). */
    private void applyMoneyTaken(PaymentEvent event, PaymentEvent.Data data) {
        Optional<Payment> found = payments.findByOrderIdForUpdate(data.orderId());
        if (found.isEmpty()) {
            if (isYoungerThan(event, ORPHAN_AFTER)) {
                // Büyük ihtimalle sipariş işlemi daha bitmedi. Biraz sonra tekrar dene.
                throw PaymentEventException.retryLater("Sipariş " + data.orderId() + " henüz görünmüyor.");
            }
            extraPayments.record(data, PaymentAlert.Kind.ORPHAN_PAYMENT,
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
                    extraPayments.record(data, PaymentAlert.Kind.SECOND_PAYMENT,
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
            default -> extraPayments.record(data, PaymentAlert.Kind.LATE_PAYMENT,
                    "Sipariş " + data.orderId() + " " + payment.getStatus() + " durumundayken ödeme geldi.");
        }
    }

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
