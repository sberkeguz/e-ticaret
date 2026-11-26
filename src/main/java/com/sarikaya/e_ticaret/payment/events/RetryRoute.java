package com.sarikaya.e_ticaret.payment.events;

/**
 * Bir dinleyici kuyruğunun tekrar deneme ve DLQ adları. Adlar ana kuyruktan türetilir:
 * i. tekrar "{kuyruk}.retry.{i}" kuyruğundan, kalıcı hata "{kuyruk}.dlq" kuyruğuna gider. Bekleme süreleri
 * her rotada aynıdır ({@link PaymentEventTopology#RETRY_DELAYS}); bekleme kuyruklarını rota sahibi tanımlar.
 */
public record RetryRoute(String mainQueue) {
    public static final RetryRoute PAYMENT_EVENTS = new RetryRoute(PaymentEventTopology.QUEUE);

    public String retryQueue(int attempt) {
        return mainQueue + ".retry." + attempt;
    }

    public String deadLetterQueue() {
        return mainQueue + ".dlq";
    }
}
