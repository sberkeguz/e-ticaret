import { Link, useNavigate } from "react-router-dom";
import { addToCart } from "../cart";
import AddToCart from "./AddToCart";
import FavoriteButton from "./FavoriteButton";
import type { ProductResponse } from "../types/api";

const money = (value: number | string | null | undefined): string => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });

interface StoreProductProps {
    product: ProductResponse;
    scrollOnOpen?: boolean;
}

export default function StoreProduct({ product, scrollOnOpen = false }: StoreProductProps) {
    const navigate = useNavigate();
    const quantity = Number(product.stock ?? 0);
    return (
        // Kartın tamamı fareyle tıklanınca ürüne gider (kolaylık); klavye ve ekran okuyucu için asıl yol
        // ürün adındaki bağlantıdır. Bağlantıya ve alttaki eylem alanına (sepet, hemen al) yapılan
        // tıklamalar kartın tıklaması sayılmaz.
        <article className="surface store-product" onClick={(event) => {
            if ((event.target as Element).closest("a, .store-product-actions")) return;
            void navigate(`/product/${product.id}`);
            if (scrollOnOpen) window.scrollTo(0, 0);
        }} style={{ cursor: "pointer" }}>
            <FavoriteButton product={product} />
            <div className="store-product-image">
                {product.imageUrl ? <img src={product.imageUrl} alt={product.name} onError={(event) => { event.currentTarget.style.display = "none"; }} /> : "▦"}
            </div>
            <div className="store-product-content">
                <div className="store-product-title">
                    <h3 className="store-product-name"><Link to={`/product/${product.id}`} onClick={() => { if (scrollOnOpen) window.scrollTo(0, 0); }}>{product.name}</Link>{product.testProduct && <span className="badge badge-amber test-badge" title="Bu ürünü sadece yöneticiler görür">Test</span>}</h3>
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
                    onClick={(event) => { event.stopPropagation(); if (addToCart(product)) void navigate("/checkout"); }}>Hemen al</button>
            </div>
        </article>
    );
}
