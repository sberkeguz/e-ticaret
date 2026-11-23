// Kargo verisinin okunması ve kargo komutlarının (kargoya ver / tekrar dene) gönderilmesi.
// Backend'den gelen gönderi alanı burada denetlenir: bilinmeyen durum ya da yanlış tipte alan
// varsa gönderi "okunamadı" (null) sayılır. Bu fonksiyonlar hata fırlatmaz ve konsola yazmaz.
import { useSyncExternalStore } from "react";
import { apiFetch } from "./api";
import type {
    AdminOrderResponse, AdminShipment, CustomerShipment, CustomerShipmentStatus, ShipmentStatus,
} from "./types/api";

const ADMIN_STATUSES = new Set<ShipmentStatus>(["REQUESTED", "CREATED", "IN_TRANSIT", "DELIVERED", "DELIVERY_FAILED", "FAILED"]);
const CUSTOMER_STATUSES = new Set<CustomerShipmentStatus>(["CREATED", "IN_TRANSIT", "DELIVERED", "DELIVERY_FAILED"]);
const MAX_REASON_LENGTH = 200;

/** Kargo komutunun cevabı bu kadar sürede gelmezse istek iptal edilir. */
export const SHIPMENT_TIMEOUT_MS = 15_000;

type Raw = Record<string, unknown>;
const isObject = (value: unknown): value is Raw => typeof value === "object" && value !== null && !Array.isArray(value);

/** Eksik alan null sayılır; metin dışında bir değer gelirse undefined (= alan bozuk). */
function optionalText(value: unknown): string | null | undefined {
    if (value === undefined || value === null) return null;
    return typeof value === "string" ? value : undefined;
}

/** Yalnız https adresini (normalleştirilmiş haliyle) döndürür; başka her şey null. */
export function safeHttpsUrl(value: unknown): string | null {
    if (typeof value !== "string" || !value.trim()) return null;
    try {
        const url = new URL(value.trim());
        if (url.protocol !== "https:" || url.username || url.password) return null;
        return url.href;
    } catch {
        return null;
    }
}

/** Yönetici gönderi alanını okur; alan yoksa ya da bozuksa null. Takip adresi https değilse null'a çevrilir. */
export function parseAdminShipment(raw: unknown): AdminShipment | null {
    if (!isObject(raw)) return null;
    const status = raw.status as ShipmentStatus;
    if (!ADMIN_STATUSES.has(status)) return null;
    if (typeof raw.statusAt !== "string") return null;
    if (typeof raw.requestNo !== "number" || !Number.isInteger(raw.requestNo) || raw.requestNo < 1) return null;
    if (raw.canRetry !== undefined && typeof raw.canRetry !== "boolean") return null;
    const carrierName = optionalText(raw.carrierName);
    const trackingNumber = optionalText(raw.trackingNumber);
    const trackingUrl = optionalText(raw.trackingUrl);
    const failureReason = optionalText(raw.failureReason);
    if (carrierName === undefined || trackingNumber === undefined || trackingUrl === undefined || failureReason === undefined) return null;
    return {
        status,
        carrierName,
        trackingNumber: trackingNumber?.trim() || null,
        trackingUrl: safeHttpsUrl(trackingUrl),
        statusAt: raw.statusAt,
        requestNo: raw.requestNo,
        failureReason: status === "FAILED" && failureReason ? failureReason.slice(0, MAX_REASON_LENGTH) : null,
        canRetry: status === "FAILED" && raw.canRetry === true,
    };
}

/**
 * Müşteri gönderi alanını okur. Müşteriye yalnız takip numarası olan gönderi gösterilir:
 * bilinmeyen/yönetici durumu (REQUESTED, FAILED), boş takip numarası ya da yanlış tip → null.
 */
export function parseCustomerShipment(raw: unknown): CustomerShipment | null {
    if (!isObject(raw)) return null;
    const status = raw.status as CustomerShipmentStatus;
    if (!CUSTOMER_STATUSES.has(status)) return null;
    if (typeof raw.trackingNumber !== "string" || !raw.trackingNumber.trim()) return null;
    if (typeof raw.statusAt !== "string") return null;
    const carrierName = optionalText(raw.carrierName);
    const trackingUrl = optionalText(raw.trackingUrl);
    if (carrierName === undefined || trackingUrl === undefined) return null;
    return { status, carrierName, trackingNumber: raw.trackingNumber.trim(), trackingUrl: safeHttpsUrl(trackingUrl), statusAt: raw.statusAt };
}

/** Siparişin gönderi alanı dolu mu (okunabilsin ya da okunamasın). Dolu ama bozuksa kargo arayüzü gösterilmez. */
export const hasShipmentField = (order: AdminOrderResponse): boolean => order.shipment !== undefined && order.shipment !== null;

/** Cevaptaki sipariş beklenen sipariş ve temel alanları yerindeyse döner, yoksa null (gönderi alanı denetlenmez). */
export function parseAdminOrder(raw: unknown, expectedId: number): AdminOrderResponse | null {
    if (!isObject(raw) || raw.id !== expectedId || typeof raw.status !== "string" || !Array.isArray(raw.items)) return null;
    return raw as unknown as AdminOrderResponse;
}

/** Kargo komutu cevabındaki sipariş: beklenen sipariş ve okunabilir bir gönderi taşıyorsa döner, yoksa null. */
export function parseCommandOrder(raw: unknown, expectedId: number): AdminOrderResponse | null {
    const order = parseAdminOrder(raw, expectedId);
    return order && parseAdminShipment(order.shipment) ? order : null;
}

// Uçuştaki kargo komutları (sipariş no). Sayfa/hücre ömründen bağımsızdır: yönetici sayfadan çıkıp
// dönse de aynı sipariş için ikinci komut gönderilemez. Komut bitince (zaman aşımı dahil) serbest kalır.
const pendingCommands = new Set<number>();
const pendingListeners = new Set<() => void>();
const orphanListeners = new Set<(orderId: number) => void>();

const subscribePending = (listener: () => void) => {
    pendingListeners.add(listener);
    return () => { pendingListeners.delete(listener); };
};

/** Sipariş için komut hakkını alır; zaten uçuşta bir komut varsa false. */
export function claimShipmentCommand(orderId: number): boolean {
    if (pendingCommands.has(orderId)) return false;
    pendingCommands.add(orderId);
    pendingListeners.forEach((listener) => listener());
    return true;
}

/**
 * Komut bitti. `orphaned`: komutu başlatan ekran artık yok (yönetici sayfadan çıktı); sonucu kimse
 * göstermediği için açık olan sipariş listesi yeniden yüklensin diye haber verilir.
 */
export function releaseShipmentCommand(orderId: number, orphaned: boolean): void {
    pendingCommands.delete(orderId);
    pendingListeners.forEach((listener) => listener());
    if (orphaned) orphanListeners.forEach((listener) => listener(orderId));
}

/** Sahipsiz kalmış bir komut bitince çağrılır; aboneliği bırakan fonksiyonu döner. */
export function onOrphanedShipmentSettled(listener: (orderId: number) => void): () => void {
    orphanListeners.add(listener);
    return () => { orphanListeners.delete(listener); };
}

/** Sipariş için uçuşta bir kargo komutu var mı (değişince bileşen yeniden çizilir). */
export function useShipmentCommandPending(orderId: number): boolean {
    return useSyncExternalStore(subscribePending, () => pendingCommands.has(orderId));
}

export type ShipmentCommandResult =
    /** order: ham cevap gövdesi (JSON değilse null); parseCommandOrder ile denetlenir. */
    | { ok: true; order: unknown }
    /** reload: sipariş başka yerde değişmiş olabilir, liste yeniden yüklenmeli. */
    | { ok: false; message: string; reload: boolean };

const MESSAGES = {
    network: "Bağlantı kurulamadı, tekrar deneyin.",
    timeout: "Sunucu zamanında yanıt vermedi. Sayfayı yenileyip kargo durumunu kontrol edin.",
    disabled: "Kargo özelliği henüz açık değil.",
    exists: "Bu sipariş zaten kargoya verildi.",
    notFound: "Sipariş bulunamadı.",
    fallback: "Kargo isteği gönderilemedi.",
};

/**
 * Kargo komutunu (POST) gönderir. Cevap SHIPMENT_TIMEOUT_MS içinde gelmezse istek iptal edilir.
 * Hata cevabı kullanıcıya gösterilecek Türkçe mesaja çevrilir. Global apiFetch'in davranışı aynen
 * kullanılır (yalnız iptal sinyali eklenir).
 */
export async function sendShipmentCommand(path: string, body?: unknown): Promise<ShipmentCommandResult> {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), SHIPMENT_TIMEOUT_MS);
    try {
        const response = await apiFetch(path, {
            method: "POST",
            body: body === undefined ? undefined : JSON.stringify(body),
            signal: controller.signal,
        });
        const data: unknown = await response.json().catch(() => null);
        if (response.ok) return { ok: true, order: data };
        return { ok: false, ...describeError(response.status, data) };
    } catch {
        return { ok: false, message: controller.signal.aborted ? MESSAGES.timeout : MESSAGES.network, reload: false };
    } finally {
        window.clearTimeout(timer);
    }
}

function describeError(status: number, data: unknown): { message: string; reload: boolean } {
    const code = isObject(data) && typeof data.code === "string" ? data.code : null;
    const message = isObject(data) && typeof data.message === "string" && data.message.trim() ? data.message.trim() : null;
    // Uç henüz yok: 405 ya da sipariş dışında bir sebeple 404 (JSON olmayan 404 dahil).
    if (status === 405 || (status === 404 && code !== "RESOURCE_NOT_FOUND")) return { message: MESSAGES.disabled, reload: false };
    if (status === 404) return { message: MESSAGES.notFound, reload: false };
    if (status === 409 && code === "SHIPMENT_EXISTS") return { message: MESSAGES.exists, reload: true };
    if (status === 409) return { message: message ?? MESSAGES.fallback, reload: true };
    return { message: message ?? MESSAGES.fallback, reload: false };
}
