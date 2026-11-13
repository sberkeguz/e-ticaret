package com.sarikaya.e_ticaret.payment.service;

import com.sarikaya.e_ticaret.payment.dto.PaymentStatusResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Ödemem ne oldu?" sorusunu bekleterek cevaplar (long polling). Ödeme hâlâ işleniyorsa cevap,
 * o siparişin ödeme olayı işlendiği anda verilir; ekranın tekrar tekrar sorması gerekmez.
 *
 * Bekleyen soru sunucuda iş parçacığı tutmaz (DeferredResult). Cevap hesaplanırken kısa süreli
 * hafif (virtual) iş parçacığı kullanılır. Süre dolarsa o anki durum ("processing") döner,
 * ekran tekrar sorar.
 */
@Component
public class PaymentStatusLongPoll {
    /** Bir sorunun en fazla bekleyebileceği süre. */
    public static final long MAX_WAIT_MS = 15_000;

    private final PaymentService paymentService;
    private final PaymentResultWaiters waiters;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public PaymentStatusLongPoll(PaymentService paymentService, PaymentResultWaiters waiters) {
        this.paymentService = paymentService;
        this.waiters = waiters;
    }

    public DeferredResult<PaymentStatusResponse> status(String username, Long orderId, long waitMs) {
        long wait = Math.clamp(waitMs, 0, MAX_WAIT_MS);
        if (wait == 0) {
            DeferredResult<PaymentStatusResponse> now = new DeferredResult<>();
            now.setResult(paymentService.paymentStatus(username, orderId));
            return now;
        }
        DeferredResult<PaymentStatusResponse> result =
                new DeferredResult<>(wait, () -> paymentService.paymentStatus(username, orderId));
        Waiter waiter = new Waiter(username, orderId, result);
        // Önce bekleyen olarak kaydol, sonra bak: arada gelen olayın haberi kaybolmasın.
        waiters.add(orderId, waiter);
        result.onCompletion(() -> waiters.remove(orderId, waiter));
        try {
            waiter.answerIfFinished();
        } catch (RuntimeException error) {
            waiters.remove(orderId, waiter);
            throw error;
        }
        return result;
    }

    /** Siparişin olayı işlenince uyanır; sonuç netse cevaplar, değilse beklemeye devam eder. */
    private final class Waiter implements Runnable {
        private final String username;
        private final Long orderId;
        private final DeferredResult<PaymentStatusResponse> result;

        private Waiter(String username, Long orderId, DeferredResult<PaymentStatusResponse> result) {
            this.username = username;
            this.orderId = orderId;
            this.result = result;
        }

        @Override
        public void run() {
            executor.execute(() -> {
                try {
                    if (!answerIfFinished() && !result.isSetOrExpired()) {
                        // Bu olay sonucu netleştirmedi (ör. başka bir denemenin olayı): beklemeye devam.
                        waiters.add(orderId, this);
                    }
                } catch (RuntimeException error) {
                    result.setErrorResult(error);
                }
            });
        }

        /** Sonuç netse cevabı verir ve true döner; hâlâ işleniyorsa false. */
        private boolean answerIfFinished() {
            if (result.isSetOrExpired()) {
                return true;
            }
            PaymentStatusResponse status = paymentService.paymentStatus(username, orderId);
            if ("processing".equals(status.result())) {
                return false;
            }
            result.setResult(status);
            waiters.remove(orderId, this);
            return true;
        }
    }
}
