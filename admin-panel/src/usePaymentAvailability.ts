import { useEffect, useState } from "react";
import { apiFetch } from "./api";

const REFRESH_MS = 10_000;

/**
 * 3D Secure'suz (2D) ödeme alınabiliyor mu? 2D ödeme RabbitMQ ile gider; RabbitMQ kapalıyken backend
 * "alınamıyor" der. Ödeme sayfası açıkken birkaç saniyede bir sorulur. Cevap alınamazsa (ağ hatası)
 * önceki bilinen durum korunur; ilk açılışta alınabilir sayılır.
 */
export default function usePaymentAvailability(enabled = true): boolean {
    const [available, setAvailable] = useState(true);

    useEffect(() => {
        if (!enabled) return undefined;
        let active = true;
        const refresh = async () => {
            try {
                const response = await apiFetch("/api/payments/availability");
                if (!response.ok) return;
                const body: { available?: boolean } = await response.json();
                if (active && typeof body.available === "boolean") setAvailable(body.available);
            } catch {
                /* geçici ağ hatası: bilinen durum kalır */
            }
        };
        refresh();
        const timer = setInterval(refresh, REFRESH_MS);
        return () => { active = false; clearInterval(timer); };
    }, [enabled]);

    return available;
}
