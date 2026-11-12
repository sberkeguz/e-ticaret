import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { apiFetch } from "../api";
import StoreHeader from "../components/StoreHeader";
import { clearRemoved } from "../cart";

import { ORDER_STEPS, CANCELLED } from "../orderStatus";
import { PAYMENT_REASON_KEY } from "./Checkout";

const money = (value) => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });
const formatDate = (value) => new Date(value).toLocaleString("tr-TR", { dateStyle: "medium", timeStyle: "short" });

function Tracker({ status }) {
    if (status === CANCELLED || status === "Ödeme bekliyor" || status === "Ödeme inceleniyor" || status === "Ödeme süresi doldu") {
        return <span className="badge badge-gray">{status}</span>;
    }
    const current = Math.max(ORDER_STEPS.indexOf(status), 0);
    return (
        <ol className="order-track">
            {ORDER_STEPS.map((step, index) => (
                <li key={step} className={index < current ? "done" : index === current ? "current" : ""}>
                    <span className="order-dot">{index <= current ? "✓" : index + 1}</span>
                    <span className="order-step">{step}</span>
                </li>
            ))}
        </ol>
    );
}

const formatLongDate = (value) => new Date(value).toLocaleString("tr-TR", { dateStyle: "long", timeStyle: "short" });

function OrderDetails({ orderId }) {
    const [detail, setDetail] = useState(null);
    const [error, setError] = useState("");

    useEffect(() => {
        let active = true;
        apiFetch(`/api/orders/${orderId}/details`)
            .then(async (response) => {
                if (!response.ok) throw new Error("Sipariş detayları yüklenemedi.");
                const data = await response.json();
                if (active) setDetail(data);
            })
            .catch((loadError) => { if (active) setError(loadError.message || "Sipariş detayları yüklenemedi."); });
        return () => { active = false; };
    }, [orderId]);

    if (error) return <p className="error-banner" role="alert">{error}</p>;
    if (!detail) return <p className="section-caption">Detaylar yükleniyor...</p>;

    const paid = Number(detail.paidAmount) > 0;
    return (
        <div className="order-detail">
            <dl className="order-detail-facts">
                <div><dt>Ödeme durumu</dt><dd><span className={`badge ${paid ? "badge-green" : "badge-gray"}`}>{detail.paymentStatus}</span></dd></div>
                <div><dt>Sipariş tarihi</dt><dd>{formatLongDate(detail.createdAt)}</dd></div>
                <div><dt>Ödenen tutar</dt><dd><b>{money(detail.paidAmount)}</b>{!paid && <span className="section-caption"> (sipariş tutarı {money(detail.total)})</span>}</dd></div>
            </dl>

            <h3 className="order-detail-title">Alınan ürünler</h3>
            <div className="order-detail-table" role="table" aria-label="Alınan ürünler">
                <div className="order-detail-row order-detail-head" role="row">
                    <span role="columnheader">Ürün</span>
                    <span role="columnheader">Marka</span>
                    <span role="columnheader">Adet</span>
                    <span role="columnheader">Birim fiyat</span>
                    <span role="columnheader">Tutar</span>
                </div>
                {detail.items.map((item) => (
                    <div className="order-detail-row" role="row" key={item.productId}>
                        <span role="cell" data-label="Ürün"><Link to={`/product/${item.productId}`}>{item.productName}</Link></span>
                        <span role="cell" data-label="Marka">{item.brand || "—"}</span>
                        <span role="cell" data-label="Adet">{item.quantity}</span>
                        <span role="cell" data-label="Birim fiyat">{money(item.price)}</span>
                        <span role="cell" data-label="Tutar"><b>{money(item.lineTotal)}</b></span>
                    </div>
                ))}
                <div className="order-detail-row order-detail-total" role="row">
                    <span role="cell">Toplam</span><span /><span /><span />
                    <span role="cell"><b>{money(detail.total)}</b></span>
                </div>
            </div>

            <h3 className="order-detail-title">Teslimat adresi</h3>
            <p className="order-detail-address">
                {detail.firstName} {detail.lastName} · {detail.phone}<br />
                {detail.neighborhood} Mah. {detail.address}<br />
                {detail.district} / {detail.city}
            </p>
        </div>
    );
}

export default function MyOrders() {
    const navigate = useNavigate();
    const [searchParams, setSearchParams] = useSearchParams();
    const paymentResult = searchParams.get("payment");
    // Reddedilme sebebi ödeme ekranından bu sekmenin hafızasıyla gelir (adresten okunmaz). Bir kez gösterilir.
    const [paymentReason] = useState(() => {
        try {
            const reason = sessionStorage.getItem(PAYMENT_REASON_KEY);
            sessionStorage.removeItem(PAYMENT_REASON_KEY);
            return paymentResult === "declined" ? reason : null;
        } catch {
            return null;
        }
    });
    const [cancellingId, setCancellingId] = useState(null);
    const [openDetails, setOpenDetails] = useState(() => new Set());
    const toggleDetails = (id) => setOpenDetails((current) => {
        const next = new Set(current);
        if (next.has(id)) next.delete(id); else next.add(id);
        return next;
    });

    const cancelOrder = async (order) => {
        if (!window.confirm("Bu sipariş iptal edilsin mi? Ayrılan stok serbest bırakılır.")) return;
        setError("");
        setCancellingId(order.id);
        try {
            const response = await apiFetch(`/api/payments/orders/${order.id}/cancel`, { method: "POST" });
            if (!response.ok) {
                const body = await response.json().catch(() => null);
                throw new Error(body?.message || "Sipariş iptal edilemedi.");
            }
            setOrders((current) => current.map((item) => (item.id === order.id ? { ...item, status: CANCELLED } : item)));
            setSearchParams({ payment: "cancelled" }, { replace: true });
        } catch (cancelError) {
            setError(cancelError.message || "Sipariş iptal edilemedi.");
        } finally {
            setCancellingId(null);
        }
    };
    const [orders, setOrders] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");

    useEffect(() => {
        if (paymentResult !== "success" && paymentResult !== "review") return;
        clearRemoved();
        localStorage.setItem("cart", "[]");
        window.dispatchEvent(new Event("cart-changed"));
    }, [paymentResult]);

    useEffect(() => {
        let active = true;
        apiFetch("/api/orders")
            .then(async (response) => {
                if (!response.ok) throw new Error("Siparişler yüklenemedi.");
                const data = await response.json();
                if (active) setOrders(data);
            })
            .catch((loadError) => { if (active) setError(loadError.message || "Siparişler yüklenemedi."); })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, []);

    return (
        <div className="storefront">
            <StoreHeader />
            <main className="store-content">
                <h1 className="page-title">Siparişlerim</h1>
                {paymentResult === "success" && <p className="success-banner" role="status">Ödemeniz onaylandı, siparişiniz hazırlanıyor.</p>}
                {paymentResult === "review" && <p className="error-banner" role="status">Ödemeniz alındı ve kontrol ediliyor.</p>}
                {paymentResult === "failed" && <p className="error-banner" role="status">Ödeme tamamlanmadı. Sipariş iptal edildi ve stok serbest bırakıldı.</p>}
                {paymentResult === "declined" && <p className="error-banner" role="status">{paymentReason ? `${paymentReason}. ` : ""}Ödeme tamamlanmadı, kartınızdan para çekilmedi. Siparişi aşağıdan tekrar ödeyebilir veya iptal edebilirsiniz.</p>}
                {paymentResult === "error" && <p className="error-banner" role="status">Ödeme sonucu doğrulanamadı. Siparişinizin durumunu aşağıdan kontrol edin; sorun sürerse bizimle iletişime geçin.</p>}
                {paymentResult === "cancelled" && <p className="success-banner" role="status">Sipariş iptal edildi, ayrılan stok serbest bırakıldı.</p>}
                {error && <p className="error-banner" role="alert">{error}</p>}
                {loading ? (
                    <p className="section-caption">Yükleniyor...</p>
                ) : orders.length === 0 ? (
                    <div className="surface empty-state">
                        <h3>Henüz siparişiniz yok</h3>
                        <Link className="button button-primary" to="/home">Alışverişe başla</Link>
                    </div>
                ) : (
                    <div className="my-orders">
                        {orders.map((order) => (
                            <article className="surface surface-pad" key={order.id}>
                                <div className="toolbar">
                                    <div>
                                        <div className="section-title">{formatDate(order.createdAt)}</div>
                                    </div>
                                    <div className="order-head-actions">
                                        <b>{money(order.total)}</b>
                                        <button className="button button-quiet" type="button"
                                            aria-expanded={openDetails.has(order.id)}
                                            aria-controls={`order-detail-${order.id}`}
                                            onClick={() => toggleDetails(order.id)}>
                                            {openDetails.has(order.id) ? "Detayları gizle" : "Sipariş detayları"}
                                        </button>
                                    </div>
                                </div>
                                <Tracker status={order.status} />
                                <div style={{ marginTop: 12 }}>
                                    {order.items.map((item) => (
                                        <div key={item.productId} className="order-line">
                                            <Link to={`/product/${item.productId}`}>{item.productName}</Link>
                                            <span>{item.quantity} × {money(item.price)}</span>
                                        </div>
                                    ))}
                                </div>
                                <div className="section-caption" style={{ marginTop: 10 }}>
                                    Teslimat: {order.firstName} {order.lastName}, {order.neighborhood} Mah. {order.address}, {order.district}/{order.city}
                                </div>
                                {openDetails.has(order.id) && (
                                    <div id={`order-detail-${order.id}`}>
                                        <OrderDetails orderId={order.id} />
                                    </div>
                                )}
                                {order.status === "Ödeme bekliyor" && (
                                    <div className="order-actions">
                                        <button className="button button-primary" type="button" disabled={cancellingId === order.id}
                                            onClick={() => navigate(`/checkout?order=${order.id}`)}>
                                            Ödemeyi yap
                                        </button>
                                        <button className="button button-danger" type="button" disabled={cancellingId === order.id}
                                            onClick={() => cancelOrder(order)}>
                                            {cancellingId === order.id ? "İptal ediliyor..." : "Siparişi iptal et"}
                                        </button>
                                    </div>
                                )}
                            </article>
                        ))}
                    </div>
                )}
            </main>
        </div>
    );
}
