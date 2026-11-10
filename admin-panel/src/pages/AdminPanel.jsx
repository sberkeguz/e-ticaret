import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch } from "../api";

const cards = [
    { to: "/admin/products", icon: "▦", title: "Ürün kataloğu", description: "Ürünleri listeleyin, stok ve fiyat bilgilerini yönetin." },
    { to: "/admin/products/new", icon: "+", title: "Yeni ürün ekle", description: "Kataloğunuza yeni bir ürün ve kategori bilgisi ekleyin." },
    { to: "/admin/category", icon: "◈", title: "Kategoriler", description: "Ürünlerinizi düzenli koleksiyonlarda sınıflandırın." },
    { to: "/admin/brand", icon: "✧", title: "Markalar", description: "Mağazanızda bulunan markaları yönetin." },
    { to: "/admin/users", icon: "♙", title: "Kullanıcılar", description: "Üyelikleri, rolleri ve hesap durumlarını yönetin." },
];

export const LOW_STOCK_LIMIT = 5;

export default function AdminPanel() {
    const [stats, setStats] = useState({ products: "—", categories: "—", brands: "—", users: "—" });
    const [lowStock, setLowStock] = useState(null);
    const username = localStorage.getItem("username") || "Yönetici";

    useEffect(() => {
        let mounted = true;
        const loadCounts = async () => {
            const endpoints = [
                ["products", "/api/products"],
                ["categories", "/api/admin/categories"],
                ["brands", "/api/admin/brands"],
                ["users", "/api/admin/users"],
            ];
            const values = await Promise.all(endpoints.map(async ([key, path]) => {
                try {
                    const response = await apiFetch(path);
                    if (!response.ok) return [key, "—"];
                    const data = await response.json();
                    if (key === "products" && Array.isArray(data) && mounted) {
                        setLowStock(data
                            .filter((product) => Number(product.stock ?? 0) < LOW_STOCK_LIMIT)
                            .sort((a, b) => Number(a.stock ?? 0) - Number(b.stock ?? 0) || a.name.localeCompare(b.name, "tr")));
                    }
                    return [key, Array.isArray(data) ? data.length.toLocaleString("tr-TR") : "—"];
                } catch {
                    return [key, "—"];
                }
            }));
            if (mounted) setStats(Object.fromEntries(values));
        };
        loadCounts();
        return () => { mounted = false; };
    }, []);

    const statItems = [
        { label: "Toplam ürün", value: stats.products, icon: "▦" },
        { label: "Kategori", value: stats.categories, icon: "◈" },
        { label: "Marka", value: stats.brands, icon: "✧" },
        { label: "Kullanıcı", value: stats.users, icon: "♙" },
    ];

    return (
        <>
            <section className="store-hero" style={{ minHeight: 208 }}>
                <div>
                    <div className="eyebrow" style={{ color: "#e1e4ff" }}>YÖNETİM MERKEZİ</div>
                    <h1>Merhaba, {username} 👋</h1>
                    <p>Mağazanızın genel durumunu buradan takip edip hızlıca yönetebilirsiniz.</p>
                </div>
                <span className="brand-mark" style={{ position: "relative", zIndex: 1, width: 72, height: 72, borderRadius: 24, fontSize: 28, background: "#ffffff22", boxShadow: "none" }}>E</span>
            </section>

            <section className="stat-grid" aria-label="Mağaza özeti">
                {statItems.map((item) => (
                    <article className="surface stat-card" key={item.label}>
                        <span className="stat-icon">{item.icon}</span>
                        <span>
                            <span className="stat-value">{item.value}</span>
                            <span className="stat-label">{item.label}</span>
                        </span>
                    </article>
                ))}
            </section>

            <section className="surface surface-pad low-stock-card">
                <div className="toolbar">
                    <div>
                        <h2 className="section-title">Stoğu {LOW_STOCK_LIMIT}'in altına düşenler</h2>
                        <p className="section-caption">Tükenen ve bitmek üzere olan ürünler, en az kalandan başlayarak.</p>
                    </div>
                    <Link className="button button-quiet button-small" to="/admin/products?stock=low">Ürünlerde göster</Link>
                </div>
                {lowStock === null ? (
                    <p className="section-caption">Yükleniyor…</p>
                ) : lowStock.length === 0 ? (
                    <p className="section-caption">Bütün ürünlerin stoğu {LOW_STOCK_LIMIT} ve üzerinde. 👍</p>
                ) : (
                    <ul className="low-stock-list">
                        {lowStock.map((product) => {
                            const stock = Number(product.stock ?? 0);
                            return (
                                <li key={product.id}>
                                    <span className="low-stock-name">
                                        {product.name}
                                        {product.testProduct && <span className="badge badge-amber test-badge">Test</span>}
                                        <small>{product.brandName || "Markasız"} · #{product.id}</small>
                                    </span>
                                    <span className={`badge ${stock <= 0 ? "badge-red" : "badge-amber"}`}>{stock <= 0 ? "Tükendi" : `${stock} adet kaldı`}</span>
                                    <Link className="button button-quiet button-small" to={`/admin/products/${product.id}/edit`}>Stok güncelle</Link>
                                </li>
                            );
                        })}
                    </ul>
                )}
            </section>

            <div className="page-heading" style={{ marginTop: 32, marginBottom: 16 }}>
                <div>
                    <div className="eyebrow">HIZLI ERİŞİM</div>
                    <h2 className="section-title">Mağazanızı yönetin</h2>
                </div>
            </div>
            <section className="dashboard-grid">
                {cards.map((card) => (
                    <Link className="surface quick-link" key={card.to} to={card.to}>
                        <span className="stat-icon">{card.icon}</span>
                        <span>
                            <h3>{card.title}</h3>
                            <p>{card.description}</p>
                        </span>
                        <span className="quick-arrow" aria-hidden="true">→</span>
                    </Link>
                ))}
            </section>
        </>
    );
}
