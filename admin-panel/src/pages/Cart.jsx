import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { addToCart, getCart, getRemoved, removeFromCart, setQuantity } from "../cart";

const money = (value) => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });

export default function Cart() {
    const navigate = useNavigate();
    const [items, setItems] = useState(getCart);
    const [removed, setRemoved] = useState(getRemoved);

    useEffect(() => {
        const refresh = () => {
            setItems(getCart());
            setRemoved(getRemoved());
        };
        window.addEventListener("cart-changed", refresh);
        return () => window.removeEventListener("cart-changed", refresh);
    }, []);

    const total = items.reduce((sum, item) => sum + Number(item.price) * item.quantity, 0);

    return (
        <div className="storefront">
            <header className="store-header">
                <a className="store-logo" href="/home"><span className="brand-mark">E</span> E-Ticaret</a>
                <div style={{ flex: 1 }} />
                <button className="button button-quiet button-small" onClick={() => navigate("/home")}>← Mağazaya dön</button>
            </header>
            <main className="store-content">
                <h1 className="page-title">Sepetim</h1>
                {items.length === 0 ? (
                    <div className="surface empty-state">
                        <h3>Sepetiniz boş</h3>
                        <Link className="button button-primary" to="/home">Alışverişe başla</Link>
                    </div>
                ) : (
                    <div className="surface surface-pad cart-list">
                        {items.map((item) => (
                            <div className="cart-row" key={item.id}>
                                <Link to={`/product/${item.id}`} className="cart-name">{item.name}</Link>
                                <span>{money(item.price)}</span>
                                <span className="cart-qty">
                                    <button className="button button-quiet button-small" onClick={() => setQuantity(item.id, item.quantity - 1)}>−</button>
                                    <b>{item.quantity}</b>
                                    <button className="button button-quiet button-small" onClick={() => setQuantity(item.id, item.quantity + 1)}>+</button>
                                </span>
                                <b>{money(Number(item.price) * item.quantity)}</b>
                                <button className="button button-quiet button-small" onClick={() => removeFromCart(item.id)}>Kaldır</button>
                            </div>
                        ))}
                        <div className="cart-total">Toplam: <b>{money(total)}</b></div>
                        <button className="button button-primary" style={{ alignSelf: "flex-end" }} onClick={() => navigate("/checkout")}>Satın al</button>
                    </div>
                )}
                {removed.length > 0 && (
                    <section className="removed-section">
                        <h2 className="section-title">Sepetten çıkardıklarınız</h2>
                        <div className="removed-scroller">
                            {removed.map((item) => (
                                <div className="surface removed-card" key={item.id}>
                                    <Link to={`/product/${item.id}`} className="cart-name">{item.name}</Link>
                                    <b>{money(item.price)}</b>
                                    <button className="button button-primary button-small" onClick={() => addToCart(item)}>Sepete geri ekle</button>
                                </div>
                            ))}
                        </div>
                    </section>
                )}
            </main>
        </div>
    );
}
