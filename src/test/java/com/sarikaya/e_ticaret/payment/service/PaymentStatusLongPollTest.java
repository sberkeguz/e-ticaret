package com.sarikaya.e_ticaret.payment.service;

import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.payment.dto.PaymentStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Ödemeniz işleniyor..." sorusu: sonuç gelene kadar bekletilir, olay gelince hemen cevaplanır. */
class PaymentStatusLongPollTest {
    private static final PaymentStatusResponse PROCESSING = PaymentStatusResponse.of("processing");
    private static final PaymentStatusResponse SUCCESS = PaymentStatusResponse.of("success");

    private final PaymentService paymentService = mock(PaymentService.class);
    private final PaymentResultWaiters waiters = new PaymentResultWaiters();
    private final PaymentStatusLongPoll longPoll = new PaymentStatusLongPoll(paymentService, waiters);

    @Test
    void finishedPaymentIsAnsweredImmediately() {
        when(paymentService.paymentStatus("ayse", 7L)).thenReturn(SUCCESS);

        DeferredResult<PaymentStatusResponse> result = longPoll.status("ayse", 7L, 10_000);

        assertEquals(SUCCESS, result.getResult());
        assertEquals(0, waiters.waitingCount(7L));
    }

    @Test
    void withoutWaitTheCurrentStatusIsReturned() {
        when(paymentService.paymentStatus("ayse", 7L)).thenReturn(PROCESSING);

        assertEquals(PROCESSING, longPoll.status("ayse", 7L, 0).getResult());
        assertEquals(0, waiters.waitingCount(7L));
    }

    @Test
    void processingPaymentIsAnsweredWhenItsEventArrives() {
        when(paymentService.paymentStatus("ayse", 7L)).thenReturn(PROCESSING, SUCCESS);

        DeferredResult<PaymentStatusResponse> result = longPoll.status("ayse", 7L, 10_000);
        assertFalse(result.hasResult());
        assertEquals(1, waiters.waitingCount(7L));

        waiters.wake(7L); // olay işlendi ve kaydedildi

        assertEquals(SUCCESS, awaitResult(result));
    }

    @Test
    void eventThatDoesNotSettleThePaymentKeepsWaiting() {
        when(paymentService.paymentStatus("ayse", 7L)).thenReturn(PROCESSING, PROCESSING, SUCCESS);
        DeferredResult<PaymentStatusResponse> result = longPoll.status("ayse", 7L, 10_000);

        waiters.wake(7L); // ör. önceki bir denemenin olayı: sonuç hâlâ belirsiz
        awaitWaiting(7L);
        assertFalse(result.hasResult());

        waiters.wake(7L);
        assertEquals(SUCCESS, awaitResult(result));
    }

    @Test
    void eventArrivingDuringTheFirstCheckIsNotMissed() {
        // İlk kontrol "işleniyor" derken olay işleniyor: bekleyen kaydı kontrolden önce açıldığı için uyanır.
        when(paymentService.paymentStatus("ayse", 7L)).thenAnswer(call -> {
            waiters.wake(7L);
            return PROCESSING;
        }).thenReturn(SUCCESS);

        DeferredResult<PaymentStatusResponse> result = longPoll.status("ayse", 7L, 10_000);

        assertEquals(SUCCESS, awaitResult(result));
    }

    @Test
    void someoneElsesOrderLeavesNoWaiterBehind() {
        when(paymentService.paymentStatus("mehmet", 7L)).thenThrow(new ResourceNotFoundException("Sipariş bulunamadı."));

        assertThrows(ResourceNotFoundException.class, () -> longPoll.status("mehmet", 7L, 10_000));
        assertEquals(0, waiters.waitingCount(7L));
    }

    @Test
    void waitIsCappedAndOtherOrdersAreNotWoken() {
        when(paymentService.paymentStatus("ayse", 7L)).thenReturn(PROCESSING);
        DeferredResult<PaymentStatusResponse> result = longPoll.status("ayse", 7L, 3_600_000);

        waiters.wake(8L);

        assertFalse(result.hasResult());
        verify(paymentService, times(1)).paymentStatus("ayse", 7L);
        assertTrue(PaymentStatusLongPoll.MAX_WAIT_MS <= 15_000);
    }

    private static PaymentStatusResponse awaitResult(DeferredResult<PaymentStatusResponse> result) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!result.hasResult() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return (PaymentStatusResponse) result.getResult();
    }

    private void awaitWaiting(Long orderId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (waiters.waitingCount(orderId) == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
