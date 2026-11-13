import { useEffect, useState } from "react";
import { apiFetch } from "../api";
import type { PaymentAlert, PaymentAlertKind } from "../types/api";

const KIND_LABELS: Record<PaymentAlertKind, string> = {
    SECOND_PAYMENT: "İkinci ödeme",
    LATE_PAYMENT: "Geç ödeme",
    ORPHAN_PAYMENT: "Siparişsiz ödeme",
};

const money = (value: number | string | null | undefined): string => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });
const when = (value: string): string => new Date(value).toLocaleString("tr-TR", { dateStyle: "short", timeStyle: "short" });

/**
 * Fazladan alınan ödemeler: sistem bunları otomatik iade eder ve burada gösterir.
 * Admin gördüğünü "Tamam" ile kapatır.
 */
export default function PaymentAlertsCard() {
    const [alerts, setAlerts] = useState<PaymentAlert[] | null>(null);
    const [error, setError] = useState("");

    useEffect(() => {
        let active = true;
        const load = () => apiFetch("/api/admin/payment-alerts")
            .then(async (response) => {
                if (!response.ok) throw new Error("Ödeme uyarıları yüklenemedi.");
                const data = await response.json();
                if (active) { setAlerts(data); setError(""); }
            })
            .catch((loadError) => { if (active) setError(loadError.message || "Ödeme uyarıları yüklenemedi."); });
        load();
        const timer = setInterval(load, 30_000);
        return () => { active = false; clearInterval(timer); };
    }, []);

    const resolve = async (id: number) => {
        const response = await apiFetch(`/api/admin/payment-alerts/${id}/resolve`, { method: "POST" });
        if (response.ok) setAlerts((current) => (current ?? []).filter((alert) => alert.id !== id));
    };

    return (
        <section className="surface surface-pad low-stock-card" aria-label="Ödeme uyarıları">
            <div className="toolbar">
                <div>
                    <h2 className="section-title">Ödeme uyarıları</h2>
                    <p className="section-caption">Fazladan alınan ödemeler otomatik iade edilir ve burada listelenir.</p>
                </div>
            </div>
            {error ? (
                <p className="error-banner" role="alert">{error}</p>
            ) : alerts === null ? (
                <p className="section-caption">Yükleniyor…</p>
            ) : alerts.length === 0 ? (
                <p className="section-caption">Bekleyen ödeme uyarısı yok. 👍</p>
            ) : (
                <ul className="low-stock-list">
                    {alerts.map((alert) => (
                        <li key={alert.id}>
                            <span className="low-stock-name">
                                Sipariş #{alert.orderId} · {money(alert.amount)}
                                <small>{alert.message} · {when(alert.createdAt)}</small>
                            </span>
                            <span className={`badge ${alert.refunded ? "badge-amber" : "badge-red"}`}>
                                {KIND_LABELS[alert.kind] ?? alert.kind} · {alert.refunded ? "İade edildi" : "Kontrol edin"}
                            </span>
                            <button type="button" className="button button-quiet button-small" onClick={() => resolve(alert.id)}>Tamam</button>
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}
