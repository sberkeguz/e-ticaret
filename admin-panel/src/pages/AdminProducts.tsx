import { useEffect, useMemo, useState } from "react";
import type { ProductResponse } from "../types/api";
import { Link, useSearchParams } from "react-router-dom";
import { LOW_STOCK_LIMIT } from "./AdminPanel";
import { apiFetch } from "../api";

const money = (value: number | string | null | undefined): string => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });

const readError = async (response: Response, fallback: string): Promise<string> => {
    const body = await response.json().catch(() => null);
    return body?.message || fallback;
};

export default function AdminProducts() {
    const [products, setProducts] = useState<ProductResponse[]>([]);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState("");
    const [searchParams] = useSearchParams();
    const [stockFilter, setStockFilter] = useState<string>(() => {
        const fromUrl = searchParams.get("stock") ?? "";
        return ["all", "in", "low", "out"].includes(fromUrl) ? fromUrl : "all";
    });

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
            .filter((value): value is string => Boolean(value)).some((value) => value.toLocaleLowerCase("tr").includes(query));
        const quantity = Number(product.stock ?? 0);
        let matchesStock = true;
        if (stockFilter === "in") matchesStock = quantity > 0;
        if (stockFilter === "low") matchesStock = quantity < LOW_STOCK_LIMIT;
        if (stockFilter === "out") matchesStock = quantity <= 0;
        return matchesSearch && matchesStock;
    }), [products, search, stockFilter]);

    const deleteProduct = async (product: ProductResponse) => {
        if (!window.confirm(`"${product.name}" ürünü silinsin mi?`)) return;
        setError("");
        try {
            const response = await apiFetch(`/api/admin/products/${product.id}`, { method: "DELETE" });
            if (!response.ok) throw new Error(await readError(response, "Ürün silinemedi."));
            setProducts((current) => current.filter((item) => item.id !== product.id));
        } catch (requestError) {
            setError((requestError as Error).message || "Ürün silinemedi.");
        }
    };

    const inStockCount = products.filter((product) => Number(product.stock ?? 0) > 0).length;
    const outOfStockCount = products.length - inStockCount;
    const lowStockCount = products.filter((product) => Number(product.stock ?? 0) < LOW_STOCK_LIMIT).length;

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

            <section className="stat-grid" aria-label="Stok filtresi">
                {[
                    { key: "all", value: products.length, label: "Toplam ürün", icon: "▦", iconStyle: undefined },
                    { key: "in", value: inStockCount, label: "Stokta bulunan", icon: "✓", iconStyle: { color: "var(--green)", background: "var(--mint)" } },
                    { key: "low", value: lowStockCount, label: `Az kalan (<${LOW_STOCK_LIMIT})`, icon: "↓", iconStyle: { color: "var(--amber)", background: "#fff4e3" } },
                    { key: "out", value: outOfStockCount, label: "Stokta olmayan", icon: "!", iconStyle: { color: "var(--red)", background: "#fdecef" } },
                ].map((card) => (
                    <button
                        key={card.key}
                        type="button"
                        className={`surface stat-card stat-filter${stockFilter === card.key ? " active" : ""}`}
                        aria-pressed={stockFilter === card.key}
                        onClick={() => setStockFilter(card.key)}
                    >
                        <span className="stat-icon" style={card.iconStyle}>{card.icon}</span>
                        <span><span className="stat-value">{card.value}</span><span className="stat-label">{card.label}</span></span>
                    </button>
                ))}
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
                            <option value="low">Az kalanlar (&lt;5)</option>
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
                                    const quantity = Number(product.stock ?? 0);
                                    return (
                                        <tr key={product.id}>
                                            <td>
                                                <div className="product-name-cell">
                                                    <span className="product-thumb">
                                                        {product.imageUrl ? <img src={product.imageUrl} alt="" onError={(event) => { event.currentTarget.style.display = "none"; }} /> : "▦"}
                                                    </span>
                                                    <span><span className="cell-primary">{product.name}{product.testProduct && <span className="badge badge-amber test-badge">Test</span>}</span><span className="cell-sub">{product.brandName || "Markasız"} · #{product.id}</span></span>
                                                </div>
                                            </td>
                                            <td>{(product.categoryNames || []).join(", ") || "—"}</td>
                                            <td className="cell-primary">{money(product.price)}</td>
                                            <td>{quantity}</td>
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
