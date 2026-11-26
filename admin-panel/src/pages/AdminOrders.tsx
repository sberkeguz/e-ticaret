import { useEffect, useRef, useState, type ReactNode } from "react";
import type { AdminOrderResponse, OrderStatsResponse, OrderStatus, RankedItem, TrendItem } from "../types/api";
import { useSearchParams } from "react-router-dom";
import { apiFetch } from "../api";
import { ORDER_STEPS, CANCELLED, orderStatusLabel } from "../orderStatus";
import AdminShipmentCell from "../components/AdminShipmentCell";
import { hasShipmentField, onOrphanedShipmentSettled, parseAdminOrder, parseAdminShipment, parseCommandOrder } from "../shipment";

const money = (value: number | string | null | undefined): string => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });
const formatDate = (value: string): string => new Date(value).toLocaleString("tr-TR", { dateStyle: "medium", timeStyle: "short" });

interface RankListProps<T extends { id: number; name: string }> {
    title: string;
    icon: string;
    /** null: yükleniyor. */
    items: T[] | null;
    empty: string;
    renderValue: (item: T) => ReactNode;
}

function RankList<T extends { id: number; name: string }>({ title, icon, items, empty, renderValue }: RankListProps<T>) {
    return (
        <article className="surface insight-card">
            <h3 className="insight-title"><span className="stat-icon">{icon}</span>{title}</h3>
            {items === null ? (
                <p className="section-caption">Yükleniyor...</p>
            ) : items.length === 0 ? (
                <p className="section-caption">{empty}</p>
            ) : (
                <ol className="insight-list">
                    {items.map((item, index) => (
                        <li key={item.id ?? item.name}>
                            <span className="insight-rank">{index + 1}</span>
                            <span className="insight-name">{item.name}</span>
                            <span className="insight-value">{renderValue(item)}</span>
                        </li>
                    ))}
                </ol>
            )}
        </article>
    );
}

const soldValue = (item: RankedItem) => <><b>{item.quantity} adet</b><span className="section-caption">{money(item.revenue)}</span></>;
const trendValue = (item: TrendItem) => {
    const change = item.previous > 0 ? Math.round(((item.recent - item.previous) / item.previous) * 100) : null;
    return (
        <>
            <b className="insight-up">▲ {change === null ? "Yeni" : `%${change}`}</b>
            <span className="section-caption">{item.previous} → {item.recent} adet</span>
        </>
    );
};

const PAGE_SIZE = 50;

const SHIPMENT_LOCKED = "Kargo durumu kargo firmasından güncellenir.";
/** Yalnız kargo olaylarından gelen durumlar: hiçbir siparişte elle seçilemez. */
const CARRIER_STATUSES = new Set<OrderStatus>(["SHIPPED", "DELIVERED"]);
/** Elle seçilebilen akış adımları (adım çubuğundaki ORDER_STEPS'ten kargo durumları çıkarılmış). */
const MANUAL_STEPS = ORDER_STEPS.filter((status) => !CARRIER_STATUSES.has(status));

/**
 * Durum kutusu kuralı: seçenekler mevcut durum + hazırlanıyor + iptal ("Kargoya verildi" ve
 * "Teslim edildi" hiç yok). Kutu salt okunur: sipariş zaten kargoya verilmiş/teslim edilmişse ya da
 * gönderi kaydı varsa (okunamıyorsa da). İstisna: kargo kaydı hiç oluşmadıysa (FAILED) kutu açık
 * kalır, sipariş iptal edilebilir.
 */
function statusOptions(order: AdminOrderResponse): { locked: boolean; options: OrderStatus[] } {
    const options = [...new Set([order.status, ...MANUAL_STEPS, CANCELLED])];
    if (CARRIER_STATUSES.has(order.status)) return { locked: true, options };
    if (!hasShipmentField(order)) return { locked: false, options };
    return { locked: parseAdminShipment(order.shipment)?.status !== "FAILED", options };
}

export default function AdminOrders() {
    const [orders, setOrders] = useState<AdminOrderResponse[]>([]);
    const [page, setPage] = useState(0);
    const [pageInfo, setPageInfo] = useState({ totalItems: 0, totalPages: 0 });
    const [query, setQuery] = useState("");
    // error: işlem (durum değiştirme, kargo) hatası; loadError: liste yüklenemedi. İkisi aynı bantta görünür.
    const [error, setError] = useState("");
    // Son kargo komutunun başarı mesajı; sonraki işlem başlayınca ya da hata olunca kalkar.
    const [success, setSuccess] = useState("");
    const [loadError, setLoadError] = useState("");
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState("");
    const [openId, setOpenId] = useState<number | null>(null);
    const [stats, setStats] = useState<OrderStatsResponse | null>(null);
    const [statsError, setStatsError] = useState("");
    const [params, setParams] = useSearchParams();
    const userFilter = params.get("user");
    // Artınca liste aynı sayfa ve aramayla yeniden yüklenir (kargo komutu sonrası güncel durum için).
    const [reloadKey, setReloadKey] = useState(0);
    // Kargo hatasından sonraki yeniden yüklemede işlem hatası silinmez (yönetici sebebi görmeli).
    // Bu yükleme bitince (başarılı ya da başarısız) bayrak iner; sonraki yüklemeler bandı yine temizler.
    const keepErrorOnLoad = useRef(false);
    // Yalnız en son başlatılan liste isteğinin cevabı kullanılır (geç gelen eski cevap yok sayılır).
    const loadSeq = useRef(0);
    // Liste isteği sürerken satıra yapılan yerel güncellemeler: o isteğin (eski) cevabı bunları ezmez.
    const localEdits = useRef(new Map<number, { order: AdminOrderResponse; seq: number }>());

    // Arama, yazmayı bırakınca (300 ms) sunucuya gider ve ilk sayfadan başlar.
    useEffect(() => {
        const timer = setTimeout(() => { setQuery(search.trim()); setPage(0); }, 300);
        return () => clearTimeout(timer);
    }, [search]);

    // Siparişler sunucudan sayfa sayfa gelir (arama ve kullanıcı filtresi sunucuda uygulanır).
    useEffect(() => {
        let active = true;
        const seq = ++loadSeq.current;
        const isLatest = () => active && seq === loadSeq.current;
        const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE), q: query, user: userFilter ?? "" });
        apiFetch(`/api/admin/orders?${params}`)
            .then(async (response) => {
                if (!response.ok) throw new Error("Siparişler yüklenemedi.");
                const data = await response.json();
                if (!isLatest()) return;
                const edits = localEdits.current;
                // Bu istek başladıktan sonra yerelde güncellenen satır, sunucunun o anki (eski) halinden yenidir.
                setOrders((data.items as AdminOrderResponse[]).map((item) => {
                    const edit = edits.get(item.id);
                    return edit && edit.seq >= seq ? edit.order : item;
                }));
                edits.clear();
                setPageInfo({ totalItems: data.totalItems, totalPages: data.totalPages });
                setLoadError("");
                if (keepErrorOnLoad.current) keepErrorOnLoad.current = false;
                else setError("");
            })
            .catch((failure) => {
                if (!isLatest()) return;
                keepErrorOnLoad.current = false;
                setLoadError(failure.message || "Siparişler yüklenemedi.");
            })
            .finally(() => { if (isLatest()) setLoading(false); });
        return () => { active = false; };
    }, [page, query, userFilter, reloadKey]);

    // Sayfadan çıkılırken uçuşta kalan kargo komutu bitince sonucu görünsün diye liste yeniden yüklenir.
    useEffect(() => onOrphanedShipmentSettled(() => setReloadKey((key) => key + 1)), []);

    useEffect(() => {
        let active = true;
        apiFetch("/api/admin/orders/stats")
            .then(async (response) => {
                if (!response.ok) throw new Error("Satış istatistikleri yüklenemedi.");
                const data = await response.json();
                if (active) setStats(data);
            })
            .catch((loadError) => { if (active) setStatsError(loadError.message || "Satış istatistikleri yüklenemedi."); });
        return () => { active = false; };
    }, []);


    const [savingId, setSavingId] = useState<number | null>(null);

    /** Satırı yerelde günceller ve sürmekte olan liste isteğinin eski cevabına karşı korur. */
    const updateRow = (orderId: number, update: (item: AdminOrderResponse) => AdminOrderResponse) => {
        setOrders((current) => current.map((item) => {
            if (item.id !== orderId) return item;
            const next = update(item);
            localEdits.current.set(orderId, { order: next, seq: loadSeq.current });
            return next;
        }));
    };

    const changeStatus = async (order: AdminOrderResponse, status: OrderStatus) => {
        if (status === order.status) return;
        if (status === CANCELLED && !window.confirm(
            `#${order.id} numaralı sipariş iptal edilecek. Ödemesi alındıysa ${money(order.total)} müşteriye iade edilir ve stok geri bırakılır. Devam edilsin mi?`)) {
            return;
        }
        setError("");
        setSuccess("");
        setSavingId(order.id);
        try {
            const response = await apiFetch(`/api/admin/orders/${order.id}/status`, { method: "PATCH", body: JSON.stringify({ status }) });
            const body = await response.json().catch(() => null);
            if (!response.ok) throw new Error(body?.message || "Durum güncellenemedi.");
            // Cevaptaki sipariş (kargo alanlarıyla) satırın yerine geçer; okunamazsa yalnız durum değişir.
            const updated = parseAdminOrder(body, order.id);
            updateRow(order.id, (item) => updated ?? { ...item, status });
        } catch (statusError) {
            // Kutu, siparişin kayıtlı durumunu gösterdiği için hata olunca kendiliğinden eski değerine döner.
            setError(`#${order.id}: ${(statusError as Error).message || "Durum güncellenemedi."}`);
        } finally {
            setSavingId(null);
        }
    };

    // Kargo komutu kabul edildi: okunabilen cevap satırı günceller, okunamayan cevapta liste yeniden yüklenir.
    const shipmentAccepted = (orderId: number, raw: unknown, command: "ship" | "retry") => {
        setSuccess(command === "ship" ? `#${orderId} için kargo kaydı istendi.` : `#${orderId} için kargo yeniden istendi.`);
        const updated = parseCommandOrder(raw, orderId);
        if (updated) updateRow(orderId, () => updated);
        else setReloadKey((key) => key + 1);
    };
    const shipmentFailed = (orderId: number, message: string, reload: boolean) => {
        setSuccess("");
        setError(`#${orderId}: ${message}`);
        if (!reload) return;
        keepErrorOnLoad.current = true;
        setReloadKey((key) => key + 1);
    };

    return (
        <>
            <div className="page-heading">
                <div>
                    <div className="eyebrow">YÖNETİM</div>
                    <h1 className="page-title">Siparişler</h1>
                    <p className="page-subtitle">Kimin ne sipariş verdiğini görüntüleyin.</p>
                </div>
                <span className="badge badge-gray">{orders.length} sipariş</span>
            </div>

            <div className="stat-grid">
                <article className="surface stat-card"><span className="stat-icon">▤</span><span><span className="stat-value">{orders.length}</span><span className="stat-label">Toplam sipariş</span></span></article>
            </div>

            {statsError ? <p className="error-banner" role="alert">{statsError}</p> : (
                <div className="insight-grid">
                    <RankList title="En çok alınan ürünler" icon="★" items={stats?.topProducts ?? null}
                        empty="Henüz tamamlanmış satış yok." renderValue={soldValue} />
                    <RankList title="En çok kullanılan kategoriler" icon="◆" items={stats?.topCategories ?? null}
                        empty="Henüz kategorili satış yok." renderValue={soldValue} />
                    <RankList title="Yükselişteki ürünler" icon="↗" items={stats?.trendingProducts ?? null}
                        empty={`Son ${stats?.trendWindowDays ?? 7} günde satışı artan ürün yok.`} renderValue={trendValue} />
                    <RankList title="Yükselişteki kategoriler" icon="↗" items={stats?.trendingCategories ?? null}
                        empty={`Son ${stats?.trendWindowDays ?? 7} günde satışı artan kategori yok.`} renderValue={trendValue} />
                </div>
            )}
            {stats && (
                <p className="section-caption insight-note">
                    Yalnızca hazırlanan, kargoya verilen ve teslim edilen siparişler sayılır. Yükseliş: son {stats.trendWindowDays} gün, önceki {stats.trendWindowDays} güne göre.
                </p>
            )}

            {/* Bantlar yapışkan: sayfa aşağı kaydırılmışken yapılan işlemin sonucu da görünür alanda çıkar,
                işlem yapılan satır yerinde kalır. role="status"/"alert" ekran okuyucuya duyurur. */}
            <div className="sticky-banners">
                {success && <p className="success-banner" role="status">{success}</p>}
                {(error || loadError) && <p className="error-banner" role="alert">{[error, loadError].filter(Boolean).join(" ")}</p>}
            </div>
            {userFilter && (
                <p style={{ marginBottom: 14 }}>
                    <button className="button button-quiet button-small" onClick={() => { setParams({}); setPage(0); }}>Kullanıcı: {userFilter} ✕</button>
                </p>
            )}

            <section className="surface surface-pad">
                <div className="toolbar">
                    <h2 className="section-title">Sipariş listesi</h2>
                    <div className="search-field"><span className="search-glyph">⌕</span><input className="input" placeholder="Kullanıcı, ad, telefon veya ürün ara..." value={search} onChange={(event) => setSearch(event.target.value)} /></div>
                </div>
                <p className="section-caption">{SHIPMENT_LOCKED} “Kargoya verildi” ve “Teslim edildi” elle seçilemez.</p>
                {loading ? (
                    <p className="section-caption">Yükleniyor...</p>
                ) : orders.length === 0 ? (
                    <div className="empty-state"><div className="empty-icon">▤</div><h3>{search ? "Eşleşen sipariş bulunamadı" : "Henüz sipariş yok"}</h3></div>
                ) : (
                    <div className="table-wrap">
                        <table className="data-table">
                            <thead>
                                <tr><th className="sticky-col">No</th><th>Kullanıcı</th><th>Alıcı</th><th>Ürünler</th><th>Tutar</th><th>Tarih</th><th>Durum</th><th>Kargo</th></tr>
                            </thead>
                            <tbody>
                                {orders.map((order) => {
                                    const { locked, options } = statusOptions(order);
                                    return (
                                        <tr key={order.id} onClick={() => setOpenId(openId === order.id ? null : order.id)} style={{ cursor: "pointer", verticalAlign: "top" }}>
                                            <td className="sticky-col">#{order.id}</td>
                                            <td><b>{order.username}</b></td>
                                            <td>
                                                {order.firstName} {order.lastName}
                                                <div className="section-caption">{order.phone}</div>
                                                {openId === order.id && <div className="section-caption">{order.neighborhood} Mah. {order.address}, {order.district}/{order.city}</div>}
                                            </td>
                                            <td>
                                                {order.items.map((item) => (
                                                    <div key={item.productId}>{item.productName} × {item.quantity}</div>
                                                ))}
                                            </td>
                                            <td><b>{money(order.total)}</b></td>
                                            <td>{formatDate(order.createdAt)}</td>
                                            <td onClick={(event) => event.stopPropagation()}>
                                                <select className="input" value={order.status} disabled={savingId === order.id || order.status === CANCELLED || locked}
                                                    title={order.status === CANCELLED ? "İptal edilen sipariş yeniden açılamaz." : locked ? SHIPMENT_LOCKED : undefined}
                                                    onChange={(event) => changeStatus(order, event.target.value as OrderStatus)}>
                                                    {options.map((status) => <option key={status} value={status}>{orderStatusLabel(status)}</option>)}
                                                </select>
                                            </td>
                                            <td className="shipment-cell" onClick={(event) => event.stopPropagation()}>
                                                <AdminShipmentCell order={order} onStart={() => { setError(""); setSuccess(""); }}
                                                    onSuccess={shipmentAccepted} onFailure={shipmentFailed} />
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                )}
                {pageInfo.totalPages > 1 && (
                    <div className="toolbar" style={{ marginTop: 12 }}>
                        <span className="section-caption">{pageInfo.totalItems} sipariş · sayfa {page + 1} / {pageInfo.totalPages}</span>
                        <div style={{ display: "flex", gap: 8 }}>
                            <button className="button button-quiet button-small" disabled={page === 0} onClick={() => setPage(page - 1)}>‹ Önceki</button>
                            <button className="button button-quiet button-small" disabled={page + 1 >= pageInfo.totalPages} onClick={() => setPage(page + 1)}>Sonraki ›</button>
                        </div>
                    </div>
                )}
            </section>
        </>
    );
}
