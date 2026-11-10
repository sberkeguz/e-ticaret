import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { addToCart, getCart, getRemoved, removeFromCart, setQuantity } from "../cart";
import StoreHeader from "../components/StoreHeader";
import FavoriteButton from "../components/FavoriteButton";
import StoreProduct from "../components/StoreProduct";
import { apiFetch } from "../api";
import useFavorites from "../useFavorites";

const money = (value) => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });

export default function Cart() {
    const navigate = useNavigate();
    const [items, setItems] = useState(getCart);
    const [removed, setRemoved] = useState(getRemoved);
    const { ids: favoriteIds } = useFavorites();
    const [products, setProducts] = useState([]);
    const [productsError, setProductsError] = useState("");

    useEffect(() => {
        let active = true;
        apiFetch("/api/products")
            .then(async (response) => {
                if (!response.ok) throw new Error("Favoriler yüklenemedi.");
                const data = await response.json();
                if (active && Array.isArray(data)) setProducts(data);
            })
            .catch((loadError) => { if (active) setProductsError(loadError.message || "Favoriler yüklenemedi."); });
        return () => { active = false; };
    }, []);

    const favorites = products.filter((product) => favoriteIds.includes(String(product.id)));

    useEffect(() => {
        const refresh = () => {
            setItems(getCart());
            setRemoved(getRemoved());
        };
        window.addEventListener("cart-changed", refresh);
        return () => window.removeEventListener("cart-changed", refresh);
    }, []);

    const total = items.reduce((sum, item) => sum + Number(item.price) * item.quantity, 0);
    const hasUnavailable = items.some((item) => item.quantity > Number(item.stock ?? 0));

    return (
        <div className="storefront">
            <StoreHeader />
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
                                <div>
                                    <Link to={`/product/${item.id}`} className="cart-name">{item.name}</Link>
                                    {item.quantity > Number(item.stock ?? 0) && <div className="cart-unavailable">Yeterli stok yok (kalan: {item.stock ?? 0}). Bu ürün sipariş edilemez.</div>}
                                </div>
                                <span>{money(item.price)}</span>
                                <span className="cart-qty">
                                    <button className="button button-quiet button-small" onClick={() => setQuantity(item.id, item.quantity - 1)}>−</button>
                                    <b>{item.quantity}</b>
                                    <button className="button button-quiet button-small" disabled={item.quantity >= Number(item.stock ?? 0)} onClick={() => setQuantity(item.id, item.quantity + 1)}>+</button>
                                </span>
                                <b>{money(Number(item.price) * item.quantity)}</b>
                                <div>
                                    {item.quantity > Number(item.stock ?? 0) && <FavoriteButton product={item} showLabel />}
                                    <button className="button button-quiet button-small" onClick={() => removeFromCart(item.id)}>Sepetten çıkar</button>
                                </div>
                            </div>
                        ))}
                        <div className="cart-total">Toplam: <b>{money(total)}</b></div>
                        {hasUnavailable && <p className="error-banner" role="alert" style={{ alignSelf: "flex-end" }}>Stoğu yetmeyen ürünleri çıkarın veya adetlerini azaltın.</p>}
                        <button className="button button-primary" style={{ alignSelf: "flex-end" }} disabled={hasUnavailable} onClick={() => navigate("/checkout")}>Satın al</button>
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
                                    <button className="button button-primary button-small" disabled={Number(item.stock ?? 0) <= 0} onClick={() => addToCart(item)}>{Number(item.stock ?? 0) <= 0 ? "Tükendi" : "Sepete geri ekle"}</button>
                                </div>
                            ))}
                        </div>
                    </section>
                )}
                {(favorites.length > 0 || productsError) && (
                    <section className="removed-section">
                        <div className="toolbar">
                            <h2 className="section-title">Favorilerim</h2>
                            <Link className="button button-quiet button-small" to="/favorites">Tümünü gör</Link>
                        </div>
                        {productsError
                            ? <p className="error-banner" role="alert">{productsError}</p>
                            : <div className="store-grid">{favorites.map((product) => <StoreProduct key={product.id} product={product} />)}</div>}
                    </section>
                )}
            </main>
        </div>
    );
}
