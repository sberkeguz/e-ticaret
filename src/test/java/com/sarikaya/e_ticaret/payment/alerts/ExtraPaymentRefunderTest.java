package com.sarikaya.e_ticaret.payment.alerts;

import com.sarikaya.e_ticaret.exception.NotRefundableException;
import com.sarikaya.e_ticaret.exception.PaymentServiceUnavailableException;
import com.sarikaya.e_ticaret.payment.client.IyzicoServiceClient;
import com.sarikaya.e_ticaret.payment.events.PaymentEvent;
import com.sarikaya.e_ticaret.payment.events.PaymentEventException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExtraPaymentRefunderTest {
    private static final UUID ATTEMPT = UUID.fromString("0b6c1f7e-1111-4222-8333-944455566677");

    private final IyzicoServiceClient iyzicoService = mock(IyzicoServiceClient.class);
    private final PaymentAlertRepository alerts = mock(PaymentAlertRepository.class);
    private final ExtraPaymentRefunder refunder = new ExtraPaymentRefunder(iyzicoService, alerts, "127.0.0.1");

    @Test
    void refundsAndWritesAlertForAdmin() {
        refunder.refund(payment("iyzico-1"), PaymentAlert.Kind.LATE_PAYMENT, "Geç ödeme.");

        verify(iyzicoService).refund(ATTEMPT, "127.0.0.1");
        PaymentAlert alert = savedAlert();
        assertEquals(PaymentAlert.Kind.LATE_PAYMENT, alert.getKind());
        assertTrue(alert.isRefunded());
        assertEquals(42L, alert.getOrderId());
        assertEquals(new BigDecimal("25.00"), alert.getAmount());
    }

    @Test
    void alreadyRefundedAttemptStillLeavesAnAlertButNoSecondRefund() {
        when(iyzicoService.refund(ATTEMPT, "127.0.0.1")).thenThrow(new NotRefundableException("Durumu REFUNDED"));

        refunder.refund(payment("iyzico-1"), PaymentAlert.Kind.SECOND_PAYMENT, "İkinci ödeme.");

        assertFalse(savedAlert().isRefunded());
    }

    @Test
    void serviceDownMeansTryAgainLater() {
        when(iyzicoService.refund(ATTEMPT, "127.0.0.1")).thenThrow(new PaymentServiceUnavailableException());

        PaymentEventException error = assertThrows(PaymentEventException.class,
                () -> refunder.refund(payment("iyzico-1"), PaymentAlert.Kind.ORPHAN_PAYMENT, "Sahipsiz."));
        assertTrue(error.retryable());
        verify(alerts, never()).save(any());
    }

    @Test
    void withoutIyzicoPaymentIdAHumanMustLook() {
        PaymentEventException error = assertThrows(PaymentEventException.class,
                () -> refunder.refund(payment(null), PaymentAlert.Kind.LATE_PAYMENT, "Geç ödeme."));
        assertFalse(error.retryable());
        verifyNoInteractions(iyzicoService);
    }

    @Test
    void sameExtraPaymentIsNeverRefundedTwice() {
        when(alerts.existsByAttemptId(ATTEMPT)).thenReturn(true);

        refunder.refund(payment("iyzico-1"), PaymentAlert.Kind.LATE_PAYMENT, "Geç ödeme.");

        verifyNoInteractions(iyzicoService);
    }

    private PaymentAlert savedAlert() {
        ArgumentCaptor<PaymentAlert> captor = ArgumentCaptor.forClass(PaymentAlert.class);
        verify(alerts).save(captor.capture());
        return captor.getValue();
    }

    private static PaymentEvent.Data payment(String iyzicoPaymentId) {
        return new PaymentEvent.Data(ATTEMPT, 42L, "SUCCEEDED", new BigDecimal("25.00"), "TRY", false, iyzicoPaymentId, null);
    }
}
