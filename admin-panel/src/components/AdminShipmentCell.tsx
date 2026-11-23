import { useEffect, useRef, useState } from "react";
import ConfirmDialog from "./ConfirmDialog";
import { SHIPMENT_STATUS_LABELS } from "../orderStatus";
import {
    claimShipmentCommand, hasShipmentField, parseAdminShipment, releaseShipmentCommand, sendShipmentCommand,
    useShipmentCommandPending,
} from "../shipment";
import type { AdminOrderResponse, ShipmentStatus } from "../types/api";

const BADGE: Record<ShipmentStatus, string> = {
    REQUESTED: "badge-amber",
    CREATED: "badge-green",
    IN_TRANSIT: "badge-green",
    DELIVERED: "badge-green",
    DELIVERY_FAILED: "badge-red",
    FAILED: "badge-red",
};

type Command = "ship" | "retry";

interface AdminShipmentCellProps {
    order: AdminOrderResponse;
    /** Komut gönderilmeden hemen önce (ör. eski hata bandını temizlemek için). */
    onStart: (orderId: number) => void;
    /** Komut kabul edildi; `raw` cevap gövdesidir (okunamıyorsa liste yeniden yüklenmeli). */
    onSuccess: (orderId: number, raw: unknown, command: Command) => void;
    onFailure: (orderId: number, message: string, reload: boolean) => void;
}

/**
 * Sipariş listesinin "Kargo" hücresi: gönderi durumu, firma, takip numarası (pano varsa "Kopyala"),
 * oluşturulamadıysa sebebi; "Kargoya ver" / "Tekrar dene" düğmeleri onay penceresiyle çalışır.
 * Gönderi alanı dolu ama okunamıyorsa hiçbir kargo arayüzü gösterilmez ("—"). "Tekrar dene" yalnız
 * sipariş hazırlanıyorken (iptal/süresi dolmuş siparişte backend yanlışlıkla canRetry dese de) görünür.
 * Başarılı komuttan sonra odak yeni durum etiketine taşınır (pencere kapanınca sayfaya düşmesin).
 */
export default function AdminShipmentCell({ order, onStart, onSuccess, onFailure }: AdminShipmentCellProps) {
    const [confirming, setConfirming] = useState<Command | null>(null);
    const [copied, setCopied] = useState(false);
    // Bu sipariş için uçuşta komut var mı (bu hücreden ya da sayfadan çıkılmadan önce başlatılmış).
    const sending = useShipmentCommandPending(order.id);
    const mounted = useRef(false);
    const badgeRef = useRef<HTMLSpanElement>(null);
    const focusBadge = useRef(false);

    useEffect(() => {
        mounted.current = true;
        return () => { mounted.current = false; };
    }, []);

    // Başarılı komuttan sonra yeni durum etiketi çizilince odağı ona ver.
    useEffect(() => {
        if (focusBadge.current && badgeRef.current) {
            focusBadge.current = false;
            badgeRef.current.focus();
        }
    });

    useEffect(() => {
        if (!copied) return undefined;
        const timer = window.setTimeout(() => setCopied(false), 2000);
        return () => window.clearTimeout(timer);
    }, [copied]);

    const shipment = parseAdminShipment(order.shipment);
    const canCopy = typeof navigator !== "undefined" && typeof navigator.clipboard?.writeText === "function";

    const run = async (command: Command) => {
        if (command === "retry" && !shipment) return;
        // Çift tıklamada ikinci tık düğme kilitlenmeden gelebilir: kilit sipariş no ile, modül düzeyinde.
        if (!claimShipmentCommand(order.id)) return;
        onStart(order.id);
        const result = command === "ship"
            ? await sendShipmentCommand(`/api/admin/orders/${order.id}/shipment`)
            : await sendShipmentCommand(`/api/admin/orders/${order.id}/shipment/retry`, { expectedRequestNo: shipment!.requestNo });
        releaseShipmentCommand(order.id, !mounted.current);
        if (!mounted.current) return;
        setConfirming(null);
        focusBadge.current = result.ok;
        if (result.ok) onSuccess(order.id, result.order, command);
        else onFailure(order.id, result.message, result.reload);
    };

    const copy = (text: string) => {
        navigator.clipboard.writeText(text).then(() => setCopied(true), () => { /* pano reddetti: numara ekranda duruyor */ });
    };

    let content;
    if (shipment) {
        content = (
            <>
                <span ref={badgeRef} tabIndex={-1} className={`badge ${BADGE[shipment.status]}`}>{SHIPMENT_STATUS_LABELS[shipment.status]}</span>
                {shipment.carrierName && <div className="section-caption">{shipment.carrierName}</div>}
                {shipment.trackingNumber && (
                    <div>
                        <code>{shipment.trackingNumber}</code>
                        {canCopy && (
                            <button className="button button-quiet button-small" type="button" onClick={() => copy(shipment.trackingNumber!)}>
                                {copied ? "Kopyalandı" : "Kopyala"}
                            </button>
                        )}
                    </div>
                )}
                {shipment.status === "FAILED" && (
                    <div className="section-caption">{shipment.failureReason ?? "Kargo firması gönderiyi oluşturamadı."}</div>
                )}
                {shipment.status === "FAILED" && shipment.canRetry && order.status === "PREPARING" && (
                    <button className="button button-quiet button-small" type="button" disabled={sending} onClick={() => setConfirming("retry")}>
                        {sending ? "Yeniden deneniyor…" : "Tekrar dene"}
                    </button>
                )}
            </>
        );
    } else if (!hasShipmentField(order) && order.canShip === true && order.status === "PREPARING") {
        content = (
            <button className="button button-primary button-small" type="button" disabled={sending} onClick={() => setConfirming("ship")}>
                {sending ? "Kargoya veriliyor…" : "Kargoya ver"}
            </button>
        );
    } else {
        content = "—";
    }

    return (
        <>
            {content}
            {confirming === "ship" && (
                <ConfirmDialog title="Kargoya ver" confirmLabel="Evet, kargoya ver" busyLabel="Kargoya veriliyor…" busy={sending}
                    onConfirm={() => { void run("ship"); }} onCancel={() => setConfirming(null)}>
                    <p>#{order.id} numaralı sipariş için kargo kaydı istenecek. Kayıt istendikten sonra sipariş bu ekrandan iptal edilemez ve durumu kargo firmasından güncellenir.</p>
                </ConfirmDialog>
            )}
            {confirming === "retry" && (
                <ConfirmDialog title="Kargoyu tekrar dene" confirmLabel="Evet, tekrar dene" busyLabel="Yeniden deneniyor…" busy={sending}
                    onConfirm={() => { void run("retry"); }} onCancel={() => setConfirming(null)}>
                    <p>#{order.id} numaralı sipariş için kargo kaydı yeniden istenecek.</p>
                </ConfirmDialog>
            )}
        </>
    );
}
