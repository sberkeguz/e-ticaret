import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiFetch } from "../api";

const money = (v) => Number(v).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });

export default function UserHome() {
    const navigate = useNavigate();
    const username = localStorage.getItem("username");

    // Sunucudan gelen veriler
    const [brands, setBrands] = useState([]);
    const [categories, setCategories] = useState([]); // düz liste: {id, name, parentId}
    const [latest, setLatest] = useState([]);
    const [results, setResults] = useState([]);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);

    // Filtreler
    const [searchInput, setSearchInput] = useState("");
    const [search, setSearch] = useState("");          // gecikmeli (debounce) hali
    const [brandIds, setBrandIds] = useState([]);
    const [categoryPath, setCategoryPath] = useState([]); // seçili kategori zinciri: [Elektronik, Telefon]
    const [minPrice, setMinPrice] = useState("");
    const [maxPrice, setMaxPrice] = useState("");
    const [inStock, setInStock] = useState(false);

    const hasFilter =
        search.trim() || brandIds.length || categoryPath.length || minPrice || maxPrice || inStock;

    // İlk yükleme: markalar, kategoriler, son eklenenler
    useEffect(() => {
        const loadAll = async () => {
            try {
                const [b, c, l] = await Promise.all([
                    apiFetch("/api/user/brands"),
                    apiFetch("/api/user/categories"),
                    apiFetch("/api/user/products/latest"),
                ]);
                if (b.ok) setBrands(await b.json());
                if (c.ok) setCategories(await c.json());
                if (l.ok) setLatest(await l.json());
            } catch {
                setError("Sunucuya ulaşılamadı.");
            } finally {
                setLoading(false);
            }
        };
        loadAll();
    }, []);

    // Arama kutusu: yazmayı bıraktıktan 400 ms sonra aramayı başlat (her tuşta istek atmamak için)
    useEffect(() => {
        const t = setTimeout(() => setSearch(searchInput), 400);
        return () => clearTimeout(t);
    }, [searchInput]);

    // Bir kategorinin kendisi + tüm alt kategorilerinin id'leri
    const collectIds = (rootId) => {
        const ids = [];
        const seen = new Set(); // döngü olursa sonsuza gitmesin
        const walk = (id) => {
            if (seen.has(id)) return;
            seen.add(id);
            ids.push(id);
            categories.filter((c) => c.parentId === id).forEach((c) => walk(c.id));
        };
        walk(rootId);
        return ids;
    };

    // Filtre/arama değişince sonuçları getir
    useEffect(() => {
        if (!hasFilter) {
            setResults([]);
            return;
        }
        const params = new URLSearchParams();
        if (search.trim()) params.set("search", search.trim());
        if (brandIds.length) params.set("brandIds", brandIds.join(","));
        if (categoryPath.length) {
            const last = categoryPath[categoryPath.length - 1];
            params.set("categoryIds", collectIds(last).join(","));
        }
        if (minPrice) params.set("minPrice", minPrice);
        if (maxPrice) params.set("maxPrice", maxPrice);
        if (inStock) params.set("inStock", "true");

        const load = async () => {
            try {
                const res = await apiFetch(`/api/user/products?${params.toString()}`);
                if (res.ok) setResults(await res.json());
                else setError("Ürünler yüklenemedi.");
            } catch {
                setError("Sunucuya ulaşılamadı.");
            }
        };
        load();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [search, brandIds, categoryPath, minPrice, maxPrice, inStock, categories]);

    // Kategori çubuğunun satırları: kökler, seçilen kategorinin çocukları, onun çocukları...
    const childrenOf = (parentId) => categories.filter((c) => (c.parentId ?? null) === parentId);
    const rows = [];
    let parent = null;
    for (let level = 0; level <= categoryPath.length; level++) {
        const items = childrenOf(parent);
        if (items.length === 0) break;
        rows.push(items);
        parent = categoryPath[level] ?? null;
        if (parent === null) break;
    }

    const pickCategory = (level, id) => {
        // Aynısına tekrar basınca seçim ve altındakiler kalkar
        if (categoryPath[level] === id) setCategoryPath(categoryPath.slice(0, level));
        else setCategoryPath([...categoryPath.slice(0, level), id]);
    };

    const toggleBrand = (id) =>
        setBrandIds(brandIds.includes(id) ? brandIds.filter((x) => x !== id) : [...brandIds, id]);

    const clearAll = () => {
        setSearchInput("");
        setSearch("");
        setBrandIds([]);
        setCategoryPath([]);
        setMinPrice("");
        setMaxPrice("");
        setInStock(false);
    };

    const logout = () => {
        localStorage.clear();
        navigate("/login");
    };

    const ProductCard = ({ p }) => (
        <div style={styles.product}>
            <div style={{ fontWeight: 600, fontSize: 17 }}>{p.name}</div>
            <div style={{ color: "#666", fontSize: 14 }}>{p.brandName} · {p.categoryName}</div>
            <div style={{ marginTop: 8, fontWeight: 600 }}>{money(p.price)}</div>
            <div style={{ color: p.stock > 0 ? "#198754" : "#dc3545", fontSize: 14 }}>
                {p.stock > 0 ? `Stokta: ${p.stock}` : "Tükendi"}
            </div>
        </div>
    );

    return (
        <div style={styles.page}>
            {/* Üst bar: solda başlık, sağda arama + çıkış */}
            <header style={styles.header}>
                <h1 style={{ margin: 0, fontSize: 22 }}>Hoş geldin, {username}</h1>
                <div style={styles.headerRight}>
                    <input
                        style={styles.search}
                        placeholder="Ürün ara..."
                        value={searchInput}
                        onChange={(e) => setSearchInput(e.target.value)}
                    />
                    <button style={styles.logout} onClick={logout}>Çıkış Yap</button>
                </div>
            </header>

            {/* Kategori çubuğu */}
            {rows.length > 0 && (
                <nav style={styles.catBar}>
                    {rows.map((items, level) => (
                        <div key={level} style={styles.catRow}>
                            {items.map((c) => (
                                <button
                                    key={c.id}
                                    onClick={() => pickCategory(level, c.id)}
                                    style={categoryPath[level] === c.id ? styles.catActive : styles.cat}
                                >
                                    {c.name}
                                </button>
                            ))}
                        </div>
                    ))}
                </nav>
            )}

            {error && <p style={styles.error}>{error}</p>}

            <div style={styles.body}>
                {/* Sol: filtreler */}
                <aside style={styles.sidebar}>
                    <h3 style={{ marginTop: 0 }}>Filtreler</h3>

                    <div style={styles.group}>
                        <strong>Marka</strong>
                        {brands.length === 0 && <div style={styles.muted}>Marka yok</div>}
                        {brands.map((b) => (
                            <label key={b.id} style={styles.check}>
                                <input
                                    type="checkbox"
                                    checked={brandIds.includes(b.id)}
                                    onChange={() => toggleBrand(b.id)}
                                />{" "}
                                {b.name}
                            </label>
                        ))}
                    </div>

                    <div style={styles.group}>
                        <strong>Fiyat</strong>
                        <div style={{ display: "flex", gap: 6, marginTop: 6 }}>
                            <input style={styles.priceInput} type="number" min="0" placeholder="En az"
                                   value={minPrice} onChange={(e) => setMinPrice(e.target.value)} />
                            <input style={styles.priceInput} type="number" min="0" placeholder="En çok"
                                   value={maxPrice} onChange={(e) => setMaxPrice(e.target.value)} />
                        </div>
                    </div>

                    <div style={styles.group}>
                        <label style={styles.check}>
                            <input type="checkbox" checked={inStock} onChange={(e) => setInStock(e.target.checked)} />{" "}
                            Sadece stokta olanlar
                        </label>
                    </div>

                    {hasFilter && <button style={styles.clear} onClick={clearAll}>Filtreleri temizle</button>}
                </aside>

                {/* Orta: son eklenenler ya da sonuçlar */}
                <main style={styles.main}>
                    {loading ? (
                        <p>Yükleniyor...</p>
                    ) : hasFilter ? (
                        <section>
                            <h2 style={styles.h2}>Sonuçlar ({results.length})</h2>
                            {results.length === 0 ? (
                                <p style={styles.muted}>Aramanıza uygun ürün bulunamadı.</p>
                            ) : (
                                <div style={styles.grid}>
                                    {results.map((p) => <ProductCard key={p.id} p={p} />)}
                                </div>
                            )}
                        </section>
                    ) : latest.length > 0 ? (
                        <section>
                            <h2 style={styles.h2}>Son eklenenler</h2>
                            <div style={styles.grid}>
                                {latest.map((p) => <ProductCard key={p.id} p={p} />)}
                            </div>
                        </section>
                    ) : null}
                </main>
            </div>
        </div>
    );
}

const styles = {
    page: { maxWidth: 1200, margin: "0 auto", padding: 24, fontFamily: "system-ui, sans-serif" },
    header: { display: "flex", justifyContent: "space-between", alignItems: "center", gap: 16, flexWrap: "wrap" },
    headerRight: { display: "flex", gap: 8, alignItems: "center" },
    search: { padding: "8px 12px", fontSize: 15, border: "1px solid #bbb", borderRadius: 20, width: 260 },
    logout: { padding: "6px 12px", cursor: "pointer" },
    catBar: { marginTop: 16, borderBottom: "1px solid #ddd", paddingBottom: 8 },
    catRow: { display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 6 },
    cat: { padding: "6px 14px", border: "1px solid #ddd", borderRadius: 16, background: "#fff", cursor: "pointer" },
    catActive: { padding: "6px 14px", border: "1px solid #0d6efd", borderRadius: 16, background: "#0d6efd", color: "#fff", cursor: "pointer" },
    body: { display: "flex", gap: 24, marginTop: 16, alignItems: "flex-start" },
    sidebar: { width: 220, flexShrink: 0, padding: 16, border: "1px solid #ddd", borderRadius: 8 },
    group: { marginBottom: 16, display: "flex", flexDirection: "column", gap: 4 },
    check: { display: "block", cursor: "pointer", fontSize: 14 },
    priceInput: { width: "50%", padding: 6, border: "1px solid #bbb", borderRadius: 4, boxSizing: "border-box" },
    clear: { width: "100%", padding: 8, cursor: "pointer" },
    main: { flex: 1, minWidth: 0 },
    h2: { marginTop: 0 },
    grid: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(200px, 1fr))", gap: 12 },
    product: { padding: 16, border: "1px solid #ddd", borderRadius: 8 },
    muted: { color: "#888", fontSize: 14 },
    error: { color: "#dc3545" },
};