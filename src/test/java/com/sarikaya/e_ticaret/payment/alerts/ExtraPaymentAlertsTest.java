package com.sarikaya.e_ticaret.payment.alerts;

import com.sarikaya.e_ticaret.payment.events.PaymentEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Fazla ödeme: para iade edilmez, admin'e yalnızca bir kez uyarı yazılır. */
class ExtraPaymentAlertsTest {
    private static final UUID ATTEMPT = UUID.fromString("0b6c1f7e-1111-4222-8333-944455566677");

    private final PaymentAlertRepository alerts = mock(PaymentAlertRepository.class);
    private final ExtraPaymentAlerts extraPayments = new ExtraPaymentAlerts(alerts);

    @Test
    void writesAnAlertWithoutRefunding() {
        ArgumentCaptor<PaymentAlert> saved = ArgumentCaptor.forClass(PaymentAlert.class);

        extraPayments.record(payment("iyzico-1"), PaymentAlert.Kind.LATE_PAYMENT, "Geç ödeme.");

        verify(alerts).save(saved.capture());
        assertFalse(saved.getValue().isRefunded());
        assertEquals(PaymentAlert.Kind.LATE_PAYMENT, saved.getValue().getKind());
        assertEquals(42L, saved.getValue().getOrderId());
        assertTrue(saved.getValue().getMessage().contains("iade edilmedi"));
    }

    @Test
    void sameAttemptIsRecordedOnlyOnce() {
        when(alerts.existsByAttemptId(ATTEMPT)).thenReturn(true);

        extraPayments.record(payment("iyzico-1"), PaymentAlert.Kind.SECOND_PAYMENT, "İkinci ödeme.");

        verify(alerts, never()).save(any());
    }

    @Test
    void paymentWithoutIyzicoNumberIsStillRecorded() {
        extraPayments.record(payment(null), PaymentAlert.Kind.ORPHAN_PAYMENT, "Siparişsiz ödeme.");

        verify(alerts).save(any());
    }

    private static PaymentEvent.Data payment(String iyzicoPaymentId) {
        return new PaymentEvent.Data(ATTEMPT, 42L, "SUCCEEDED", new BigDecimal("25.00"), "TRY", false, iyzicoPaymentId, null);
    }
}
