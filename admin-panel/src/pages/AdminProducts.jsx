import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch } from "../api";

const money = (value) => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });

const readError = async (response, fallback) => {
    const body = await response.json().catch(() => null);
    return body?.message || fallback;
};

export default function AdminProducts() {
    const [products, setProducts] = useState([]);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState("");
    const [stockFilter, setStockFilter] = useState("all");

    useEffect(() => {
        let active = true;
        apiFetch("/api/products")
            .then(async (response) => {
                if (!response.ok) throw new Error(await readError(response, "Ürünler yüklenemedi."));
                const data = await response.json();
                if (active) setProducts(data);
            })
            .catch((loadError) => { if (active) setError(loadError.message || "Ürünler yüklenemedi."); })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, []);

    const visibleProducts = useMemo(() => products.filter((product) => {
        const query = search.trim().toLocaleLowerCase("tr");
        const matchesSearch = !query || [product.name, product.brandName, ...(product.categoryNames || [])]
            .filter(Boolean).some((value) => value.toLocaleLowerCase("tr").includes(query));
        const quantity = Number(product.quantity ?? product.stock ?? 0);
        const matchesStock = stockFilter === "all" || (stockFilter === "in" ? quantity > 0 : quantity === 0);
        return matchesSearch && matchesStock;
    }), [products, search, stockFilter]);

    const deleteProduct = async (product) => {
        if (!window.confirm(`"${product.name}" ürünü silinsin mi?`)) return;
        setError("");
        try {
            const response = await apiFetch(`/api/admin/products/${product.id}`, { method: "DELETE" });
            if (!response.ok) throw new Error(await readError(response, "Ürün silinemedi."));
            setProducts((current) => current.filter((item) => item.id !== product.id));
        } catch (requestError) {
            setError(requestError.message || "Ürün silinemedi.");
        }
    };

    const inStockCount = products.filter((product) => Number(product.quantity ?? product.stock ?? 0) > 0).length;
    const outOfStockCount = products.length - inStockCount;

    return (
        <>
            <div className="page-heading">
                <div>
                    <div className="eyebrow">KATALOG YÖNETİMİ</div>
                    <h1 className="page-title">Ürünler</h1>
                    <p className="page-subtitle">Ürün kataloğunuzu, fiyatlarınızı ve stok durumunuzu yönetin.</p>
                </div>
                <Link className="button button-primary" to="/admin/products/new">+ Yeni ürün</Link>
            </div>

            <section className="stat-grid" style={{ gridTemplateColumns: "repeat(3, minmax(0, 1fr))" }}>
                <article className="surface stat-card"><span className="stat-icon">▦</span><span><span className="stat-value">{products.length}</span><span className="stat-label">Toplam ürün</span></span></article>
                <article className="surface stat-card"><span className="stat-icon" style={{ color: "var(--green)", background: "var(--mint)" }}>✓</span><span><span className="stat-value">{inStockCount}</span><span className="stat-label">Stokta bulunan</span></span></article>
                <article className="surface stat-card"><span className="stat-icon" style={{ color: "var(--amber)", background: "#fff4e3" }}>!</span><span><span className="stat-value">{outOfStockCount}</span><span className="stat-label">Stokta olmayan</span></span></article>
            </section>

            <section className="surface surface-pad">
                <div className="toolbar">
                    <div>
                        <h2 className="section-title">Ürün kataloğu</h2>
                        <p className="section-caption">{visibleProducts.length} ürün listeleniyor</p>
                    </div>
                    <div className="toolbar-right">
                        <div className="search-field">
                            <span className="search-glyph">⌕</span>
                            <input className="input" placeholder="Ürün, marka veya kategori ara..." value={search} onChange={(event) => setSearch(event.target.value)} />
                        </div>
                        <select className="select" style={{ width: 150 }} value={stockFilter} onChange={(event) => setStockFilter(event.target.value)}>
                            <option value="all">Tüm stoklar</option>
                            <option value="in">Stokta olanlar</option>
                            <option value="out">Stokta olmayanlar</option>
                        </select>
                    </div>
                </div>
                {error && <p className="error-banner" role="alert">{error}</p>}
                {loading ? <div className="loading-state">Ürün kataloğu yükleniyor…</div> : visibleProducts.length === 0 ? (
                    <div className="empty-state">
                        <div className="empty-icon">▦</div>
                        <h3>{products.length ? "Eşleşen ürün bulunamadı" : "Kataloğunuz henüz boş"}</h3>
                        <p>{products.length ? "Arama veya stok filtresini değiştirmeyi deneyin." : "Mağazanıza ilk ürünü ekleyerek başlayın."}</p>
                        {!products.length && <Link className="button button-primary" to="/admin/products/new">+ İlk ürünü oluştur</Link>}
                    </div>
                ) : (
                    <div className="table-wrap">
                        <table className="data-table">
                            <thead><tr><th>Ürün</th><th>Kategori</th><th>Fiyat</th><th>Stok</th><th>Durum</th><th style={{ textAlign: "right" }}>İşlemler</th></tr></thead>
                            <tbody>
                                {visibleProducts.map((product) => {
                                    const quantity = Number(product.quantity ?? product.stock ?? 0);
                                    return (
                                        <tr key={product.id}>
                                            <td>
                                                <div className="product-name-cell">
                                                    <span className="product-thumb">
                                                        {product.imageUrl ? <img src={product.imageUrl} alt="" onError={(event) => { event.currentTarget.style.display = "none"; }} /> : "▦"}
                                                    </span>
                                                    <span><span className="cell-primary">{product.name}</span><span className="cell-sub">{product.brandName || "Markasız"} · #{product.id}</span></span>
                                                </div>
                                            </td>
                                            <td>{(product.categoryNames || []).join(", ") || product.categoryName || "—"}</td>
                                            <td className="cell-primary">{money(product.price)}</td>
                                            <td>{quantity} {product.unit || ""}</td>
                                            <td><span className={`badge ${quantity > 0 ? "badge-green" : "badge-amber"}`}>{quantity > 0 ? "Stokta" : "Tükendi"}</span></td>
                                            <td>
                                                <div className="button-row" style={{ justifyContent: "flex-end" }}>
                                                    <Link className="button button-quiet button-small" to={`/admin/products/${product.id}/edit`}>Düzenle</Link>
                                                    <button className="button button-danger button-small" onClick={() => deleteProduct(product)}>Sil</button>
                                                </div>
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>
        </>
    );
}
