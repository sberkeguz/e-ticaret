import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { apiFetch } from "../api";
import { parentIdsOf } from "../categoryTree";
import type { Brand, CategoryResponse, ProductPageResponse, ProductResponse } from "../types/api";
import CategoryPicker from "../components/CategoryPicker";
import StoreHeader from "../components/StoreHeader";
import StoreProduct from "../components/StoreProduct";

const getParentIds = (category: CategoryResponse): number[] => parentIdsOf(category);

const PAGE_SIZE = 24;

/**
 * Ürün listesinin ilk sayfası sekme boyunca hatırlanır: ana sayfaya dönünce (ya da sayfa yenilenince)
 * liste anında görünür, arka planda sunucudan tazelenir. Anahtar kullanıcıya göre ayrılır (admin'e
 * özel ürünler başka hesaba gösterilmez). Fiyat/stok yine her açılışta sunucudan güncellenir.
 */
const CACHE_KEY = "product-list-cache";
const CACHE_LIMIT = 10;
const readCache = (): Record<string, ProductPageResponse> => {
    try {
        return JSON.parse(sessionStorage.getItem(CACHE_KEY) ?? "{}");
    } catch {
        return {};
    }
};
const cachedPage = (key: string): ProductPageResponse | undefined => readCache()[key];
const rememberPage = (key: string, page: ProductPageResponse) => {
    try {
        const cache = readCache();
        delete cache[key];
        const kept = Object.entries(cache).slice(-(CACHE_LIMIT - 1));
        sessionStorage.setItem(CACHE_KEY, JSON.stringify(Object.fromEntries([...kept, [key, page]])));
    } catch {
        // Depolama kapalı/dolu: önbelleksiz devam edilir.
    }
};

export default function UserHome() {
    const [categories, setCategories] = useState<CategoryResponse[]>([]);
    // Ürünler sunucudan sayfa sayfa gelir; filtreler sunucuda uygulanır.
    const [products, setProducts] = useState<ProductResponse[]>([]);
    const [totalItems, setTotalItems] = useState(0);
    const [nextPage, setNextPage] = useState(0);
    const [loadingMore, setLoadingMore] = useState(false);
    const [brands, setBrands] = useState<Brand[]>([]);
    const [error, setError] = useState("");
    // Ürünlerin ilk cevabı gelene kadar true: bu sırada "ürün yok" ekranı gösterilmez.
    const [loading, setLoading] = useState(true);
    const firstProductLoad = useRef(true);
    const [searchInput, setSearchInput] = useState("");
    const [search, setSearch] = useState("");
    const [categoryPath, setCategoryPath] = useState<number[]>(() => ((window.history.state?.usr?.categoryPath ?? []) as unknown[]).map(Number));
    const [minPrice, setMinPrice] = useState("");
    const [maxPrice, setMaxPrice] = useState("");
    const [inStock, setInStock] = useState(false);
    const [brandIds, setBrandIds] = useState<number[]>([]);
    const [pickerOpen, setPickerOpen] = useState(false);
    const hasFilter = Boolean(search.trim() || categoryPath.length || minPrice || maxPrice || inStock || brandIds.length);

    useEffect(() => {
        let active = true;
        const loadAll = async () => {
            try {
                const [categoryResponse, brandResponse] = await Promise.all([
                    apiFetch("/api/user/categories"),
                    apiFetch("/api/user/brands"),
                ]);
                const categoryData = categoryResponse.ok ? await categoryResponse.json() : [];
                const brandData: Brand[] = brandResponse.ok ? await brandResponse.json() : [];
                if (!active) return;
                setCategories(categoryData);
                setBrands([...brandData].sort((a, b) => a.name.localeCompare(b.name, "tr")));
            } catch (loadError) {
                if (active) setError((loadError as Error).message || "Sunucuya ulaşılamadı.");
            }
        };
        loadAll();
        return () => { active = false; };
    }, []);

    useEffect(() => {
        const timeout = setTimeout(() => setSearch(searchInput), 350);
        return () => clearTimeout(timeout);
    }, [searchInput]);

    const collectIds = useCallback((rootId: number): number[] => {
        const ids: number[] = [];
        const seen = new Set();
        const walk = (id: number) => {
            if (seen.has(id)) return;
            seen.add(id);
            ids.push(id);
            categories.filter((category) => getParentIds(category).includes(id)).forEach((child) => walk(child.id));
        };
        walk(rootId);
        return ids;
    }, [categories]);

    /** Filtrelerin sunucuya gidecek hâli (kategori seçilince alt kategorileri de eklenir). */
    const query = useMemo(() => {
        const deepest = categoryPath.filter((id) => !categoryPath.some((other) => other !== id && collectIds(id).includes(other)));
        const params = new URLSearchParams({ size: String(PAGE_SIZE) });
        if (search.trim()) params.set("q", search.trim());
        const categoryIds = [...new Set(deepest.flatMap(collectIds))];
        if (categoryIds.length) params.set("categoryIds", categoryIds.join(","));
        if (minPrice) params.set("minPrice", minPrice);
        if (maxPrice) params.set("maxPrice", maxPrice);
        if (inStock) params.set("inStock", "true");
        if (brandIds.length) params.set("brandIds", brandIds.join(","));
        return params.toString();
    }, [search, categoryPath, minPrice, maxPrice, inStock, brandIds, collectIds]);

    const loadPage = useCallback(async (page: number): Promise<ProductPageResponse> => {
        const response = await apiFetch(`/api/products/search?${query}&page=${page}`);
        if (!response.ok) throw new Error("Ürünler yüklenemedi.");
        return response.json();
    }, [query]);

    // Filtre değişince ilk sayfa yeniden yüklenir (yazarken her tuşta değil, 300 ms sonra).
    // Sayfa ilk açıldığında beklenmez: ürünler kategorilerle aynı anda istenir.
    useEffect(() => {
        let active = true;
        const cacheKey = `${localStorage.getItem("username") ?? ""}|${query}`;
        const cached = cachedPage(cacheKey);
        if (cached) {
            setProducts(cached.items);
            setTotalItems(cached.totalItems);
            setNextPage(1);
            setLoading(false);
        }
        const delay = firstProductLoad.current ? 0 : 300;
        const timer = setTimeout(() => {
            firstProductLoad.current = false;
            loadPage(0)
                .then((data) => {
                    if (!active) return;
                    rememberPage(cacheKey, data);
                    setProducts(data.items);
                    setTotalItems(data.totalItems);
                    setNextPage(1);
                    setError("");
                })
                .catch((loadError) => { if (active) setError((loadError as Error).message); })
                .finally(() => { if (active) setLoading(false); });
        }, delay);
        return () => { active = false; clearTimeout(timer); };
    }, [loadPage, query]);

    const loadMore = async () => {
        setLoadingMore(true);
        try {
            const data = await loadPage(nextPage);
            setProducts((current) => [...current, ...data.items.filter((item) => !current.some((existing) => existing.id === item.id))]);
            setTotalItems(data.totalItems);
            setNextPage(nextPage + 1);
        } catch (loadError) {
            setError((loadError as Error).message);
        } finally {
            setLoadingMore(false);
        }
    };

    const brandMatches = useMemo(() => {
        const query = searchInput.trim().toLowerCase();
        if (!query) return [];
        return brands.filter((brand) => brand.name.toLowerCase().includes(query) && !brandIds.includes(brand.id)).slice(0, 5);
    }, [brands, searchInput, brandIds]);

    const selectBrand = (id: number) => {
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

    return (
        <div className="storefront">
            <StoreHeader search={(
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
            )} />

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
                                <p>{loading ? "Katalog yükleniyor…" : `${totalItems} ürün listeleniyor`}</p>
                            </div>
                        </div>
                        {loading ? <div className="loading-state">Mağaza kataloğu hazırlanıyor…</div> : products.length ? (
                            <>
                                <div className="store-grid">
                                    {products.map((product) => <StoreProduct key={product.id} product={product} />)}
                                </div>
                                {products.length < totalItems && (
                                    <div style={{ display: "flex", justifyContent: "center", marginTop: 20 }}>
                                        <button className="button button-quiet" type="button" disabled={loadingMore} onClick={loadMore}>
                                            {loadingMore ? "Yükleniyor…" : `Daha fazla göster (${totalItems - products.length})`}
                                        </button>
                                    </div>
                                )}
                            </>
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
