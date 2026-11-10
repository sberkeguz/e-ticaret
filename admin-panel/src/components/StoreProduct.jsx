import { useNavigate } from "react-router-dom";
import { addToCart } from "../cart";
import AddToCart from "./AddToCart";
import FavoriteButton from "./FavoriteButton";

const money = (value) => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });

export default function StoreProduct({ product, scrollOnOpen = false }) {
    const navigate = useNavigate();
    const quantity = Number(product.stock ?? 0);
    return (
        <article className="surface store-product" onClick={() => {
            navigate(`/product/${product.id}`);
            if (scrollOnOpen) window.scrollTo(0, 0);
        }} style={{ cursor: "pointer" }}>
            <FavoriteButton product={product} />
            <div className="store-product-image">
                {product.imageUrl ? <img src={product.imageUrl} alt={product.name} onError={(event) => { event.currentTarget.style.display = "none"; }} /> : "▦"}
            </div>
            <div className="store-product-content">
                <div className="store-product-title">
                    <h3 className="store-product-name">{product.name}{product.testProduct && <span className="badge badge-amber test-badge" title="Bu ürünü sadece yöneticiler görür">Test</span>}</h3>
                    {product.brandName && <div className="store-product-brand">{product.brandName}</div>}
                </div>
                <div className="store-product-bottom">
                    <span className="store-price">{money(product.price)}</span>
                    <span className={`store-stock${quantity <= 0 ? " out" : ""}`}>{quantity > 0 ? `Stokta ${quantity}` : "Tükendi"}</span>
                </div>
            </div>
            <div className="store-product-actions">
                <div className="store-product-bottom">
                    <span className="store-price">{money(product.price)}</span>
                    <span className={`store-stock${quantity <= 0 ? " out" : ""}`}>{quantity > 0 ? `Stokta ${quantity}` : "Tükendi"}</span>
                </div>
                <AddToCart product={product} className="button button-quiet button-small" />
                <button className="button button-primary button-small" disabled={quantity <= 0}
                    onClick={(event) => { event.stopPropagation(); if (addToCart(product)) navigate("/checkout"); }}>Hemen al</button>
            </div>
        </article>
    );
}
