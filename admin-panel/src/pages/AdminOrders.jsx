import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { apiFetch } from "../api";
import { ORDER_STEPS, CANCELLED } from "../orderStatus";

const money = (value) => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });
const formatDate = (value) => new Date(value).toLocaleString("tr-TR", { dateStyle: "medium", timeStyle: "short" });

function RankList({ title, icon, items, empty, renderValue }) {
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

const soldValue = (item) => <><b>{item.quantity} adet</b><span className="section-caption">{money(item.revenue)}</span></>;
const trendValue = (item) => {
    const change = item.previous > 0 ? Math.round(((item.recent - item.previous) / item.previous) * 100) : null;
    return (
        <>
            <b className="insight-up">▲ {change === null ? "Yeni" : `%${change}`}</b>
            <span className="section-caption">{item.previous} → {item.recent} adet</span>
        </>
    );
};

export default function AdminOrders() {
    const [orders, setOrders] = useState([]);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState("");
    const [openId, setOpenId] = useState(null);
    const [stats, setStats] = useState(null);
    const [statsError, setStatsError] = useState("");
    const [params, setParams] = useSearchParams();
    const userFilter = params.get("user");

    useEffect(() => {
        let active = true;
        apiFetch("/api/admin/orders")
            .then(async (response) => {
                if (!response.ok) throw new Error("Siparişler yüklenemedi.");
                const data = await response.json();
                if (active) setOrders(data);
            })
            .catch((loadError) => { if (active) setError(loadError.message || "Siparişler yüklenemedi."); })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, []);

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

    const filtered = useMemo(() => {
        const query = search.trim().toLocaleLowerCase("tr");
        const base = userFilter ? orders.filter((order) => order.username === userFilter) : orders;
        if (!query) return base;
        return base.filter((order) =>
            [order.username, order.firstName, order.lastName, order.phone, ...order.items.map((item) => item.productName)]
                .some((value) => String(value ?? "").toLocaleLowerCase("tr").includes(query)));
    }, [orders, search, userFilter]);

    const [savingId, setSavingId] = useState(null);

    const changeStatus = async (order, status) => {
        if (status === order.status) return;
        if (status === CANCELLED && !window.confirm(
            `#${order.id} numaralı sipariş iptal edilecek. Ödemesi alındıysa ${money(order.total)} müşteriye iade edilir ve stok geri bırakılır. Devam edilsin mi?`)) {
            return;
        }
        setError("");
        setSavingId(order.id);
        try {
            const response = await apiFetch(`/api/admin/orders/${order.id}/status`, { method: "PATCH", body: JSON.stringify({ status }) });
            if (!response.ok) {
                const body = await response.json().catch(() => null);
                throw new Error(body?.message || "Durum güncellenemedi.");
            }
            setOrders((current) => current.map((item) => (item.id === order.id ? { ...item, status } : item)));
        } catch (statusError) {
            // Kutu, siparişin kayıtlı durumunu gösterdiği için hata olunca kendiliğinden eski değerine döner.
            setError(`#${order.id}: ${statusError.message || "Durum güncellenemedi."}`);
        } finally {
            setSavingId(null);
        }
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

            {error && <p className="error-banner" role="alert">{error}</p>}
            {userFilter && (
                <p style={{ marginBottom: 14 }}>
                    <button className="button button-quiet button-small" onClick={() => setParams({})}>Kullanıcı: {userFilter} ✕</button>
                </p>
            )}

            <section className="surface surface-pad">
                <div className="toolbar">
                    <h2 className="section-title">Sipariş listesi</h2>
                    <div className="search-field"><span className="search-glyph">⌕</span><input className="input" placeholder="Kullanıcı, ad veya ürün ara..." value={search} onChange={(event) => setSearch(event.target.value)} /></div>
                </div>
                {loading ? (
                    <p className="section-caption">Yükleniyor...</p>
                ) : filtered.length === 0 ? (
                    <div className="empty-state"><div className="empty-icon">▤</div><h3>{search ? "Eşleşen sipariş bulunamadı" : "Henüz sipariş yok"}</h3></div>
                ) : (
                    <div className="table-wrap">
                        <table className="data-table">
                            <thead>
                                <tr><th>No</th><th>Kullanıcı</th><th>Alıcı</th><th>Ürünler</th><th>Tutar</th><th>Tarih</th><th>Durum</th></tr>
                            </thead>
                            <tbody>
                                {filtered.map((order) => (
                                    <tr key={order.id} onClick={() => setOpenId(openId === order.id ? null : order.id)} style={{ cursor: "pointer", verticalAlign: "top" }}>
                                        <td>#{order.id}</td>
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
                                            <select className="input" value={order.status} disabled={savingId === order.id || order.status === CANCELLED}
                                                title={order.status === CANCELLED ? "İptal edilen sipariş yeniden açılamaz." : undefined}
                                                onChange={(event) => changeStatus(order, event.target.value)}>
                                                {[...new Set([order.status, ...ORDER_STEPS, CANCELLED])].map((status) => <option key={status} value={status}>{status}</option>)}
                                            </select>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>
        </>
    );
}
