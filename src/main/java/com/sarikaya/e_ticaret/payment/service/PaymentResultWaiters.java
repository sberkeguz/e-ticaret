package com.sarikaya.e_ticaret.payment.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * "Ödemeniz işleniyor..." ekranının bekleyen soruları. Ekran sonucu tekrar tekrar sormak yerine bir
 * kez sorar; cevap, o siparişin ödeme olayı işlenip veritabanına kaydedildiği anda verilir.
 *
 * Yalnızca bu backend kopyasındaki bekleyenler uyanır. Backend birden fazla kopya çalışırsa olayı
 * başka kopya işleyebilir; o zaman bekleme süresi dolar ve ekran tekrar sorar (yavaşlar, bozulmaz).
 */
@Component
public class PaymentResultWaiters {
    private final ConcurrentMap<Long, Set<Runnable>> waiting = new ConcurrentHashMap<>();

    /** Sipariş için bir bekleyen ekler; {@link #remove} ile kaldırılmalı (cevap verilince/süre dolunca). */
    public void add(Long orderId, Runnable onWake) {
        waiting.computeIfAbsent(orderId, id -> ConcurrentHashMap.newKeySet()).add(onWake);
    }

    public void remove(Long orderId, Runnable onWake) {
        waiting.computeIfPresent(orderId, (id, set) -> {
            set.remove(onWake);
            return set.isEmpty() ? null : set;
        });
    }

    /**
     * Siparişin bekleyenlerini uyandırır. Bir veritabanı işlemi içindeysek işlem kaydedildikten
     * sonra (yoksa ekran henüz kaydedilmemiş eski durumu okuyabilirdi); işlem geri alınırsa hiç.
     */
    public void wakeAfterCommit(Long orderId) {
        if (orderId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wake(orderId);
                }
            });
        } else {
            wake(orderId);
        }
    }

    void wake(Long orderId) {
        Set<Runnable> toWake = waiting.remove(orderId);
        if (toWake != null) {
            toWake.forEach(Runnable::run);
        }
    }

    int waitingCount(Long orderId) {
        Set<Runnable> set = waiting.get(orderId);
        return set == null ? 0 : set.size();
    }
}
