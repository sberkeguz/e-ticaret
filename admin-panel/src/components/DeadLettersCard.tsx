import { useEffect, useState } from "react";
import { apiFetch } from "../api";
import type { DeadLetterList } from "../types/api";

/**
 * İşlenemeyen ödeme mesajları (RabbitMQ DLQ). 3 tekrardan sonra işlenemeyen her mesaj buraya düşer.
 * Admin sorunu giderdiyse "Tekrar işle", önemsizse "Sil" der.
 */
export default function DeadLettersCard() {
    const [data, setData] = useState<DeadLetterList | null>(null);
    const [error, setError] = useState("");
    const [reloadKey, setReloadKey] = useState(0);

    useEffect(() => {
        let active = true;
        const load = () => apiFetch("/api/admin/dead-letters")
            .then(async (response) => {
                if (!response.ok) throw new Error("İşlenemeyen mesajlar yüklenemedi.");
                const body = await response.json();
                if (active) { setData(body); setError(""); }
            })
            .catch((loadError) => { if (active) setError(loadError.message || "İşlenemeyen mesajlar yüklenemedi."); });
        load();
        const timer = setInterval(load, 30_000);
        return () => { active = false; clearInterval(timer); };
    }, [reloadKey]);

    const act = async (messageId: string, method: "POST" | "DELETE", path: string) => {
        const response = await apiFetch(`/api/admin/dead-letters/${encodeURIComponent(messageId)}${path}`, { method });
        if (!response.ok) setError("İşlem yapılamadı; mesaj artık kuyrukta olmayabilir.");
        setReloadKey((key) => key + 1);
    };

    // Kuyruk boşsa kart hiç gösterilmez; sorun olduğunda dikkat çeksin.
    if (!error && (!data || data.total === 0)) return null;

    return (
        <section className="surface surface-pad low-stock-card" aria-label="İşlenemeyen mesajlar">
            <div className="toolbar">
                <div>
                    <h2 className="section-title">İşlenemeyen ödeme mesajları ({data?.total ?? "?"})</h2>
                    <p className="section-caption">3 denemede de işlenemeyen mesajlar. Sorunu giderdiyseniz tekrar işleyin.</p>
                </div>
            </div>
            {error && <p className="error-banner" role="alert">{error}</p>}
            {data && data.messages.length > 0 && (
                <ul className="low-stock-list">
                    {data.messages.map((message) => (
                        <li key={message.messageId}>
                            <span className="low-stock-name">
                                {message.type || "Mesaj"}{message.orderId ? ` · Sipariş #${message.orderId}` : ""}{message.amount ? ` · ₺${message.amount}` : ""}
                                <small>{message.error || "Sebep bilinmiyor"}{message.failedAt ? ` · ${new Date(message.failedAt).toLocaleString("tr-TR")}` : ""}</small>
                            </span>
                            <span className="badge badge-red">{message.retries} tekrar</span>
                            <span style={{ display: "flex", gap: 6 }}>
                                <button type="button" className="button button-quiet button-small" onClick={() => act(message.messageId, "POST", "/replay")}>Tekrar işle</button>
                                <button type="button" className="button button-quiet button-small" onClick={() => act(message.messageId, "DELETE", "")}>Sil</button>
                            </span>
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}
