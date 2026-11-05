import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiFetch } from "../api";
import { addToCart, cartCount } from "../cart";
import AddToCart from "../components/AddToCart";
import CategoryPicker from "../components/CategoryPicker";

const money = (value) => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });
const getParentIds = (category) => Array.isArray(category.parentIds)
    ? category.parentIds
    : category.parentId == null ? [] : [category.parentId];

export default function UserHome() {
    const navigate = useNavigate();
    const username = localStorage.getItem("username") || "Misafir";
    const [categories, setCategories] = useState([]);
    const [allProducts, setAllProducts] = useState([]);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);
    const [searchInput, setSearchInput] = useState("");
    const [search, setSearch] = useState("");
    const [categoryPath, setCategoryPath] = useState(() => (window.history.state?.usr?.categoryPath ?? []).map(Number));
    const [minPrice, setMinPrice] = useState("");
    const [maxPrice, setMaxPrice] = useState("");
    const [inStock, setInStock] = useState(false);
    const [brandIds, setBrandIds] = useState([]);
    const [pickerOpen, setPickerOpen] = useState(false);
    const [count, setCount] = useState(cartCount);

    useEffect(() => {
        const refresh = () => setCount(cartCount());
        window.addEventListener("cart-changed", refresh);
        return () => window.removeEventListener("cart-changed", refresh);
    }, []);

    const hasFilter = Boolean(search.trim() || categoryPath.length || minPrice || maxPrice || inStock || brandIds.length);

    useEffect(() => {
        let active = true;
        const loadAll = async () => {
            try {
                const [categoryResponse, productResponse] = await Promise.all([
                    apiFetch("/api/user/categories"),
                    apiFetch("/api/products"),
                ]);
                if (!productResponse.ok) throw new Error("Ürünler yüklenemedi.");
                const productData = await productResponse.json();
                const categoryData = categoryResponse.ok ? await categoryResponse.json() : [];
                if (!active) return;
                setCategories(categoryData);
                setAllProducts(productData);
            } catch (loadError) {
                if (active) setError(loadError.message || "Sunucuya ulaşılamadı.");
            } finally {
                if (active) setLoading(false);
            }
        };
        loadAll();
        return () => { active = false; };
    }, []);

    useEffect(() => {
        const timeout = setTimeout(() => setSearch(searchInput), 350);
        return () => clearTimeout(timeout);
    }, [searchInput]);

    const collectIds = useCallback((rootId) => {
        const ids = [];
        const seen = new Set();
        const walk = (id) => {
            if (seen.has(id)) return;
            seen.add(id);
            ids.push(id);
            categories.filter((category) => getParentIds(category).includes(id)).forEach((child) => walk(child.id));
        };
        walk(rootId);
        return ids;
    }, [categories]);

    const products = useMemo(() => {
        const query = search.trim().toLowerCase();
        const deepest = categoryPath.filter((id) => !categoryPath.some((other) => other !== id && collectIds(id).includes(other)));
        const allowed = new Set(deepest.flatMap(collectIds));
        return allProducts.filter((product) => {
            if (query && !product.name?.toLowerCase().includes(query)) return false;
            if (allowed.size && !(product.categoryIds ?? []).some((id) => allowed.has(id))) return false;
            if (minPrice && Number(product.price) < Number(minPrice)) return false;
            if (maxPrice && Number(product.price) > Number(maxPrice)) return false;
            if (inStock && Number(product.stock ?? 0) <= 0) return false;
            if (brandIds.length && !brandIds.includes(product.brandId)) return false;
            return true;
        });
    }, [allProducts, search, categoryPath, minPrice, maxPrice, inStock, brandIds, collectIds]);

    const brands = useMemo(() => {
        const map = new Map();
        allProducts.forEach((product) => { if (product.brandId != null) map.set(product.brandId, product.brandName); });
        return [...map].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name, "tr"));
    }, [allProducts]);

    const brandMatches = useMemo(() => {
        const query = searchInput.trim().toLowerCase();
        if (!query) return [];
        return brands.filter((brand) => brand.name.toLowerCase().includes(query) && !brandIds.includes(brand.id)).slice(0, 5);
    }, [brands, searchInput, brandIds]);

    const selectBrand = (id) => {
        setBrandIds([id]);
        setSearchInput("");
        setSearch("");
    };

    const selectedCategories = useMemo(
        () => categories.filter((category) => categoryPath.includes(category.id)),
        [categories, categoryPath],
    );

    const clearFilters = () => {
        setSearchInput("");
        setSearch("");
        setCategoryPath([]);
        setMinPrice("");
        setMaxPrice("");
        setInStock(false);
        setBrandIds([]);
        setError("");
    };

    const logout = () => {
        localStorage.clear();
        navigate("/login");
    };

    return (
        <div className="storefront">
            <header className="store-header">
                <a className="store-logo" href="/home"><span className="brand-mark">E</span> E-Ticaret</a>
                <div className="store-search search-field" style={{ position: "relative" }}>
                    <span className="search-glyph">⌕</span>
                    <input className="input" placeholder="Aradığınız ürünü yazın..." value={searchInput} onChange={(event) => setSearchInput(event.target.value)} />
                    {brandMatches.length > 0 && (
                        <div className="surface" style={{ position: "absolute", top: "100%", left: 0, right: 0, marginTop: 6, padding: 6, zIndex: 20 }}>
                            <span className="section-caption" style={{ padding: "4px 10px", display: "block" }}>Markalar</span>
                            {brandMatches.map((brand) => (
                                <button type="button" className="button button-quiet" style={{ width: "100%", justifyContent: "flex-start" }} key={brand.id} onClick={() => selectBrand(brand.id)}>
                                    {brand.name}
                                </button>
                            ))}
                        </div>
                    )}
                </div>
                <div className="store-user">
                    <span className="avatar small">{username.slice(0, 2)}</span>
                    <span className="store-user-name">Merhaba, {username}</span>
                    <button className="button button-quiet button-small" onClick={() => navigate("/cart")}>Sepet ({count})</button>
                    {localStorage.getItem("role") === "ADMIN" && (
                        <button className="button button-primary button-small" onClick={() => navigate("/admin")}>Admin görünümü</button>
                    )}
                    <button className="button button-quiet button-small" onClick={logout}>Çıkış</button>
                </div>
            </header>

            <main className="store-content">
                <section className="store-hero">
                    <div>
                        <div className="eyebrow" style={{ color: "#e1e4ff" }}>SİZİN İÇİN SEÇİLDİ</div>
                        <h1>Güzel şeyler keşfetmeye başlayın.</h1>
                        <p>Mağazamızdaki ürünleri keşfedin, size uygun olanı bulun.</p>
                    </div>
                </section>

                {brandIds.length > 0 && (
                    <nav className="store-cat-list" aria-label="Marka filtresi">
                        {brands.filter((brand) => brandIds.includes(brand.id)).map((brand) => (
                            <button className="store-cat active" key={brand.id} onClick={() => setBrandIds((current) => current.filter((id) => id !== brand.id))}>
                                Marka: {brand.name} ✕
                            </button>
                        ))}
                    </nav>
                )}
                {categories.length > 0 && (
                    <nav className="store-cat-list" aria-label="Kategori filtresi">
                        <button className="store-cat active" onClick={() => setPickerOpen(true)}>▦ Kategoriler</button>
                        {selectedCategories.map((category) => (
                            <button className="store-cat" key={category.id} onClick={() => setCategoryPath((current) => current.filter((id) => id !== category.id))}>
                                {category.name} ✕
                            </button>
                        ))}
                    </nav>
                )}
                {pickerOpen && (
                    <CategoryPicker
                        categories={categories}
                        selectedIds={categoryPath}
                        onChange={(ids) => setCategoryPath(ids.map(Number))}
                        onClose={() => setPickerOpen(false)}
                    />
                )}

                {error && <p className="error-banner" role="alert">{error}</p>}

                <div className="store-body">
                    <aside className="surface store-sidebar">
                        <div className="toolbar" style={{ marginBottom: 0 }}>
                            <h2 className="section-title">Filtreler</h2>
                            {hasFilter && <button className="button button-quiet button-small" onClick={clearFilters}>Temizle</button>}
                        </div>
                        <div className="filter-group">
                            <span className="filter-title">Fiyat aralığı</span>
                            <div className="price-pair">
                                <input className="input" type="number" min="0" placeholder="En az" value={minPrice} onChange={(event) => setMinPrice(event.target.value)} />
                                <input className="input" type="number" min="0" placeholder="En çok" value={maxPrice} onChange={(event) => setMaxPrice(event.target.value)} />
                            </div>
                        </div>
                        <div className="filter-group">
                            <label className="store-check"><input type="checkbox" checked={inStock} onChange={(event) => setInStock(event.target.checked)} /> Yalnızca stokta olanlar</label>
                        </div>
                    </aside>

                    <section>
                        <div className="store-main-head">
                            <div>
                                <h2>{hasFilter ? "Arama sonuçları" : "Yeni keşifler"}</h2>
                                <p>{loading ? "Katalog yükleniyor…" : `${products.length} ürün listeleniyor`}</p>
                            </div>
                        </div>
                        {loading ? <div className="loading-state">Mağaza kataloğu hazırlanıyor…</div> : products.length ? (
                            <div className="store-grid">
                                {products.map((product) => {
                                    const quantity = Number(product.stock ?? 0);
                                    return (
                                        <article className="surface store-product" key={product.id} onClick={() => navigate(`/product/${product.id}`)} style={{ cursor: "pointer" }}>
                                            <div className="store-product-image">
                                                {product.imageUrl ? <img src={product.imageUrl} alt={product.name} onError={(event) => { event.currentTarget.style.display = "none"; }} /> : "▦"}
                                            </div>
                                            <div className="store-product-content">
                                                <h3 className="store-product-name">{product.name}</h3>
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
                                                    onClick={(event) => { event.stopPropagation(); addToCart(product); navigate("/checkout"); }}>Hemen al</button>
                                            </div>
                                        </article>
                                    );
                                })}
                            </div>
                        ) : (
                            <div className="surface empty-state">
                                <div className="empty-icon">⌕</div>
                                <h3>{hasFilter ? "Sonuç bulunamadı" : "Ürünler yakında burada"}</h3>
                                <p>{hasFilter ? "Filtrelerinizi değiştirerek tekrar deneyin." : "Mağazaya yeni ürün eklendiğinde burada görebilirsiniz."}</p>
                                {hasFilter && <button className="button button-quiet" onClick={clearFilters}>Filtreleri temizle</button>}
                            </div>
                        )}
                    </section>
                </div>
            </main>
        </div>
    );
}
