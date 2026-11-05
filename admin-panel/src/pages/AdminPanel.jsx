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

export default function AdminPanel() {
    const [stats, setStats] = useState({ products: "—", categories: "—", brands: "—", users: "—" });
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
