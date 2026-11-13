import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { apiFetch } from "../api";
import { parentIdsOf } from "../categoryTree";
import type { CategoryResponse, ProductResponse } from "../types/api";
import { addToCart } from "../cart";
import AddToCart from "../components/AddToCart";
import StoreHeader from "../components/StoreHeader";
import StoreProduct from "../components/StoreProduct";
import FavoriteButton from "../components/FavoriteButton";

const money = (value: number | string | null | undefined): string => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });

export default function ProductDetail() {
    const { id } = useParams();
    const navigate = useNavigate();
    const [product, setProduct] = useState<ProductResponse | null>(null);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);
    const [tab, setTab] = useState("info");
    const [categories, setCategories] = useState<CategoryResponse[]>([]);
    const [allProducts, setAllProducts] = useState<ProductResponse[]>([]);

    useEffect(() => {
        apiFetch("/api/products")
            .then((response) => (response.ok ? response.json() : []))
            .then((data) => setAllProducts(Array.isArray(data) ? data : []))
            .catch(() => setAllProducts([]));
    }, []);

    useEffect(() => {
        apiFetch("/api/user/categories")
            .then((response) => (response.ok ? response.json() : []))
            .then((data) => setCategories(Array.isArray(data) ? data : []))
            .catch(() => setCategories([]));
    }, []);

    const trail = (() => {
        const byId = new Map(categories.map((category) => [Number(category.id), category]));
        const parentOf = (category: CategoryResponse) => byId.get(Number(parentIdsOf(category)[0]));
        const selected = (product?.categoryIds ?? []).map((categoryId) => byId.get(Number(categoryId))).filter((category): category is CategoryResponse => Boolean(category));
        const ancestors = new Set();
        selected.forEach((category) => {
            for (let node = parentOf(category), guard = 0; node && guard < 20; node = parentOf(node), guard += 1) ancestors.add(Number(node.id));
        });
        const deepest = selected.find((category) => !ancestors.has(Number(category.id))) ?? selected[0];
        const path = [];
        for (let node = deepest, guard = 0; node && guard < 20; node = parentOf(node), guard += 1) path.unshift({ id: Number(node.id), name: node.name });
        return path;
    })();


    useEffect(() => {
        let active = true;
        apiFetch(`/api/products/${id}`)
            .then(async (response) => {
                if (!response.ok) throw new Error(response.status === 404 ? "Ürün bulunamadı." : "Ürün yüklenemedi.");
                const data = await response.json();
                if (active) setProduct(data);
            })
            .catch((loadError) => { if (active) setError(loadError.message || "Sunucuya ulaşılamadı."); })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, [id]);

    const similar = (() => {
        const keyIds = trail.slice(-2).map((crumb) => crumb.id);
        if (!product || !keyIds.length) return [];
        return allProducts
            .filter((item) => item.id !== product.id && keyIds.every((categoryId) => (item.categoryIds ?? []).map(Number).includes(categoryId)))
            .slice(0, 8);
    })();

    const stock = Number(product?.stock ?? 0);

    return (
        <div className="storefront">
            <StoreHeader />

            <main className="store-content">
                {loading && <div className="loading-state">Ürün yükleniyor…</div>}
                {error && <p className="error-banner" role="alert">{error}</p>}
                {product && (
                    <>
                        <nav className="breadcrumb" aria-label="Kategori yolu">
                            <Link to="/home">e-ticaret_sitesi</Link>
                            {trail.map((crumb, index) => (
                                <span key={crumb.id}>
                                    <i>›</i>
                                    <a href="/home" onClick={(event) => {
                                        event.preventDefault();
                                        navigate("/home", { state: { categoryPath: trail.slice(0, index + 1).map((item) => item.id) } });
                                    }}>{crumb.name}</a>
                                </span>
                            ))}
                            <span className="current"><i>›</i>{product.name}</span>
                        </nav>
                        <article className="surface product-detail">
                        <div className="product-detail-image">
                            <FavoriteButton product={product} />
                            {product.imageUrl
                                ? <img src={product.imageUrl} alt={product.name} onError={(event) => { event.currentTarget.style.display = "none"; }} />
                                : "▦"}
                        </div>
                        <div className="product-detail-info">
                            <h1 className="page-title">{product.name}{product.testProduct && <span className="badge badge-amber test-badge" title="Bu ürünü sadece yöneticiler görür">Test ürünü</span>}</h1>
                            {product.brandName && <div className="section-caption">Marka: <strong>{product.brandName}</strong></div>}
                            <div className="store-price" style={{ fontSize: 28 }}>{money(product.price)}</div>
                            <span className={`store-stock${stock <= 0 ? " out" : ""}`}>{stock > 0 ? `Stokta ${stock} adet` : "Tükendi"}</span>
                            <div className="rating-stars" aria-label="Henüz değerlendirme yok">
                                {"★★★★★"}
                            </div>
                            <div className="product-actions">
                                <AddToCart product={product} />
                                <button className="button button-primary" disabled={stock <= 0} onClick={() => { if (addToCart(product)) navigate("/checkout"); }}>
                                    Hemen al
                                </button>
                            </div>
                        </div>
                    </article>
                    <section className="surface product-tabs" id="product-tabs">
                        <div className="product-tab-bar" role="tablist">
                            <button type="button" role="tab" aria-selected={tab === "info"} className={tab === "info" ? "active" : ""} onClick={() => setTab("info")}>Ürün Bilgileri</button>
                            <button type="button" role="tab" aria-selected={tab === "description"} className={tab === "description" ? "active" : ""} onClick={() => setTab("description")}>Ürün Açıklaması</button>
                        </div>
                        <div className="product-tab-body">
                            {tab === "info" && (
                                <>
                                    {product.imageUrl && <img className="product-tab-image" src={product.imageUrl} alt={product.name} onError={(event) => { event.currentTarget.style.display = "none"; }} />}
                                    <div>
                                        <h2 className="section-title">Ürün Açıklaması</h2>
                                        <p className="page-subtitle">{product.description || "Bu ürün için açıklama eklenmemiş."}</p>
                                    </div>
                                    <div>
                                        <h2 className="section-title">Ek Bilgiler</h2>
                                        <ul className="product-extra-list">
                                            <li>Ürün: {product.name}</li>
                                            {product.brandName && <li>Marka: {product.brandName}</li>}
                                            {product.origin && <li>Üretim yeri: {product.origin}</li>}
                                            <li>{stock > 0 ? `Stokta ${stock} adet bulunmaktadır.` : "Bu ürün şu anda tükendi."}</li>
                                            {trail.length > 0 && <li>Kategori: {trail.map((crumb) => crumb.name).join(" › ")}</li>}
                                        </ul>
                                    </div>
                                </>
                            )}
                            {tab === "description" && (
                                <div style={{ gridColumn: "1 / -1" }}>
                                    <h2 className="section-title">Ürün Açıklaması</h2>
                                    <p className="page-subtitle">{product.description || "Bu ürün için açıklama eklenmemiş."}</p>
                                </div>
                            )}
                        </div>
                    </section>
                    {similar.length > 0 && (
                        <section style={{ marginTop: 28 }}>
                            <h2 className="section-title">Benzer ürünler</h2>
                            <div className="store-grid" style={{ marginTop: 14 }}>
                                {similar.map((item) => <StoreProduct key={item.id} product={item} scrollOnOpen />)}
                            </div>
                        </section>
                    )}
                    </>
                )}
            </main>
        </div>
    );
}
