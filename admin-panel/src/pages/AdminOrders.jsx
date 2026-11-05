import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { apiFetch } from "../api";
import { ORDER_STEPS, CANCELLED } from "../orderStatus";

const money = (value) => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });
const formatDate = (value) => new Date(value).toLocaleString("tr-TR", { dateStyle: "medium", timeStyle: "short" });

export default function AdminOrders() {
    const [orders, setOrders] = useState([]);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState("");
    const [openId, setOpenId] = useState(null);
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

    const filtered = useMemo(() => {
        const query = search.trim().toLocaleLowerCase("tr");
        const base = userFilter ? orders.filter((order) => order.username === userFilter) : orders;
        if (!query) return base;
        return base.filter((order) =>
            [order.username, order.firstName, order.lastName, order.phone, ...order.items.map((item) => item.productName)]
                .some((value) => String(value ?? "").toLocaleLowerCase("tr").includes(query)));
    }, [orders, search, userFilter]);

    const changeStatus = async (id, status) => {
        setError("");
        try {
            const response = await apiFetch(`/api/admin/orders/${id}/status`, { method: "PATCH", body: JSON.stringify({ status }) });
            if (!response.ok) throw new Error("Durum güncellenemedi.");
            setOrders((current) => current.map((order) => (order.id === id ? { ...order, status } : order)));
        } catch (statusError) {
            setError(statusError.message || "Durum güncellenemedi.");
        }
    };

    const revenue = orders.reduce((sum, order) => sum + Number(order.total || 0), 0);

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
                <article className="surface stat-card"><span className="stat-icon">₺</span><span><span className="stat-value">{money(revenue)}</span><span className="stat-label">Toplam tutar</span></span></article>
            </div>

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
                                            <select className="input" value={order.status} onChange={(event) => changeStatus(order.id, event.target.value)}>
                                                {[...ORDER_STEPS, CANCELLED].map((status) => <option key={status} value={status}>{status}</option>)}
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
