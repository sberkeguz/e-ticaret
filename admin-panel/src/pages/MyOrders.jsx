import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch } from "../api";
import StoreHeader from "../components/StoreHeader";

import { ORDER_STEPS, CANCELLED } from "../orderStatus";

const money = (value) => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });
const formatDate = (value) => new Date(value).toLocaleString("tr-TR", { dateStyle: "medium", timeStyle: "short" });

function Tracker({ status }) {
    if (status === CANCELLED) return <span className="badge badge-gray">{CANCELLED}</span>;
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

export default function MyOrders() {
    const [orders, setOrders] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");

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
                                    <b>{money(order.total)}</b>
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
                            </article>
                        ))}
                    </div>
                )}
            </main>
        </div>
    );
}
