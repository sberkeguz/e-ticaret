package com.sarikaya.e_ticaret.payment.events;

import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.payment.alerts.ExtraPaymentRefunder;
import com.sarikaya.e_ticaret.payment.alerts.PaymentAlert;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.payment.service.PaymentService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentEventHandlerTest {
    private static final UUID ATTEMPT = UUID.fromString("0b6c1f7e-1111-4222-8333-944455566677");
    private static final UUID OTHER_ATTEMPT = UUID.fromString("0b6c1f7e-9999-4222-8333-944455566677");

    private final ProcessedMessages processed = mock(ProcessedMessages.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final ExtraPaymentRefunder refunder = mock(ExtraPaymentRefunder.class);
    /** Testte "şimdi" = olay zamanı + 30 sn (olay tazedir). */
    private Clock clock = Clock.fixed(Instant.parse("2026-11-12T10:00:30Z"), ZoneOffset.UTC);
    private PaymentEventHandler handler() {
        return new PaymentEventHandler(processed, payments, paymentService, refunder, clock);
    }

    @Test
    void duplicateMessageIsSkipped() {
        when(processed.markProcessed("m-1", "PaymentSucceeded")).thenReturn(false);

        handler().handle("m-1", event("SUCCEEDED", ATTEMPT));

        verifyNoInteractions(payments, paymentService, refunder);
    }

    @Test
    void successForPendingOrderCompletesIt() {
        // Senkron cevap kaybolmuş: sipariş hâlâ bekliyor, olay onu tamamlar.
        Payment payment = payment(PaymentStatus.PENDING, null);
        allowProcessing();

        handler().handle("m-1", event("SUCCEEDED", ATTEMPT));

        assertEquals(ATTEMPT, payment.getAttemptId());
        assertEquals("iyzico-1", payment.getToken());
        verify(paymentService).applyAttemptResult(payment, payment.getOrder(), "SUCCEEDED");
    }

    @Test
    void successAlreadyAppliedBySyncPathChangesNothing() {
        payment(PaymentStatus.PAID, ATTEMPT);
        allowProcessing();

        handler().handle("m-1", event("SUCCEEDED", ATTEMPT));

        verify(paymentService, never()).applyAttemptResult(any(), any(), anyString());
    }

    @Test
    void reviewTurnsIntoPaidWhenSuccessArrives() {
        Payment payment = payment(PaymentStatus.REVIEW, ATTEMPT);
        allowProcessing();

        handler().handle("m-1", event("SUCCEEDED", ATTEMPT));

        verify(paymentService).applyAttemptResult(payment, payment.getOrder(), "SUCCEEDED");
    }

    @Test
    void missingOrderIsRetriedWhileEventIsFresh() {
        // Senkron işlem henüz bitmemiş olabilir.
        allowProcessing();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.empty());

        PaymentEventException error = assertThrows(PaymentEventException.class,
                () -> handler().handle("m-1", event("SUCCEEDED", ATTEMPT)));
        assertTrue(error.retryable());
    }

    @Test
    void missingOrderAfterTwoMinutesIsRefundedAsOrphan() {
        clock = Clock.fixed(Instant.parse("2026-11-12T10:03:00Z"), ZoneOffset.UTC);
        allowProcessing();
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.empty());

        handler().handle("m-1", event("SUCCEEDED", ATTEMPT));

        verify(refunder).refund(any(), eq(PaymentAlert.Kind.ORPHAN_PAYMENT), anyString());
    }

    @Test
    void secondSuccessfulPaymentIsRefundedAutomatically() {
        payment(PaymentStatus.PAID, OTHER_ATTEMPT);
        allowProcessing();

        handler().handle("m-1", event("SUCCEEDED", ATTEMPT));

        verify(refunder).refund(argThatAttempt(ATTEMPT), eq(PaymentAlert.Kind.SECOND_PAYMENT), anyString());
        verify(paymentService, never()).applyAttemptResult(any(), any(), anyString());
    }

    @Test
    void uncertainSecondPaymentIsRefundedToo() {
        payment(PaymentStatus.PAID, OTHER_ATTEMPT);
        allowProcessing();

        handler().handle("m-1", event("VERIFYING", ATTEMPT));

        verify(refunder).refund(argThatAttempt(ATTEMPT), eq(PaymentAlert.Kind.SECOND_PAYMENT), anyString());
    }

    @Test
    void latePaymentForExpiredOrderIsRefundedAutomatically() {
        Payment payment = payment(PaymentStatus.EXPIRED, ATTEMPT);
        allowProcessing();

        handler().handle("m-1", event("SUCCEEDED", ATTEMPT));

        verify(refunder).refund(any(), eq(PaymentAlert.Kind.LATE_PAYMENT), anyString());
        assertEquals(PaymentStatus.EXPIRED, payment.getStatus());
    }

    @Test
    void latePaymentForCancelledOrderIsRefundedAutomatically() {
        payment(PaymentStatus.CANCELLED, ATTEMPT);
        allowProcessing();

        handler().handle("m-1", event("SUCCEEDED", ATTEMPT));

        verify(refunder).refund(any(), eq(PaymentAlert.Kind.LATE_PAYMENT), anyString());
    }

    @Test
    void refundedEventNeverTriggersAnotherRefund() {
        allowProcessing();

        handler().handle("m-1", event("REFUNDED", ATTEMPT));

        verifyNoInteractions(payments, paymentService, refunder);
    }

    private static PaymentEvent.Data argThatAttempt(UUID attemptId) {
        return org.mockito.ArgumentMatchers.argThat(data -> attemptId.equals(data.attemptId()));
    }

    @Test
    void failedPaymentNeedsNothingEvenWithoutOrder() {
        // Reddedilen 2D ödemede sipariş geri alındı; olay sipariş bulamaz ama para çekilmedi.
        allowProcessing();

        handler().handle("m-1", event("FAILED", ATTEMPT));

        verifyNoInteractions(payments, paymentService, refunder);
    }

    @Test
    void unknownStatusFromNewerVersionIsIgnored() {
        allowProcessing();

        handler().handle("m-1", event("SOMETHING_NEW", ATTEMPT));

        verifyNoInteractions(payments, paymentService, refunder);
    }

    @Test
    void olderEventDoesNotOverrideNewerOne() {
        // "Başarılı" (sıra 7) önce işlendi; ardından gecikmiş "doğrulanıyor" (sıra 5) geliyor.
        Payment payment = payment(PaymentStatus.PAID, ATTEMPT);
        payment.setLastEventSequence(7L);
        allowProcessing();

        handler().handle("m-2", withSequence(event("VERIFYING", ATTEMPT), 5L));

        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertEquals(7L, payment.getLastEventSequence());
        verifyNoInteractions(paymentService, refunder);
    }

    @Test
    void newerEventIsAppliedAndRemembered() {
        Payment payment = payment(PaymentStatus.PENDING, ATTEMPT);
        payment.setLastEventSequence(5L);
        allowProcessing();

        handler().handle("m-3", withSequence(event("SUCCEEDED", ATTEMPT), 7L));

        assertEquals(7L, payment.getLastEventSequence());
        verify(paymentService).applyAttemptResult(payment, payment.getOrder(), "SUCCEEDED");
    }

    private static PaymentEvent withSequence(PaymentEvent event, Long sequence) {
        return new PaymentEvent(event.messageId(), event.type(), event.version(), event.occurredAt(), event.traceId(), event.data(), sequence);
    }

    @Test
    void eventMissingOptionalFieldsIsStillProcessed() throws Exception {
        // Daha eski ya da sade bir yayıncı threeDS/version göndermese de olay okunur.
        String body = """
                {"messageId":"m-9","type":"PaymentFailed","data":{"attemptId":"%s","orderId":42,"status":"FAILED"}}"""
                .formatted(ATTEMPT);
        PaymentEvent event = tools.jackson.databind.json.JsonMapper.builder().build().readValue(body, PaymentEvent.class);
        allowProcessing();

        handler().handle("m-9", event);

        assertEquals(null, event.data().threeDS());
    }

    private void allowProcessing() {
        when(processed.markProcessed(anyString(), anyString())).thenReturn(true);
    }

    private Payment payment(PaymentStatus status, UUID attemptId) {
        Order order = new Order();
        order.setId(42L);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setStatus(status);
        payment.setAttemptId(attemptId);
        when(payments.findByOrderIdForUpdate(42L)).thenReturn(Optional.of(payment));
        return payment;
    }

    private static PaymentEvent event(String status, UUID attemptId) {
        String type = switch (status) {
            case "SUCCEEDED" -> "PaymentSucceeded";
            case "FAILED" -> "PaymentFailed";
            case "VERIFYING" -> "PaymentVerifying";
            case "REFUNDED" -> "PaymentRefunded";
            default -> "PaymentSomething";
        };
        return new PaymentEvent("m-1", type, 1, "2026-11-12T10:00:00Z", "t".repeat(32),
                new PaymentEvent.Data(attemptId, 42L, status, new BigDecimal("25.00"), "TRY", false, "iyzico-1", null), null);
    }
}
