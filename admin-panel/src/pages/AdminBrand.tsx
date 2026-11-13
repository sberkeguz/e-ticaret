import { useEffect, useState, type FormEvent } from "react";
import type { Brand } from "../types/api";
import { apiFetch } from "../api";

const readError = async (response: Response, fallback: string): Promise<string> => {
    const body = await response.json().catch(() => null);
    return body?.message || fallback;
};

export default function AdminBrand() {
    const [brands, setBrands] = useState<Brand[]>([]);
    const [name, setName] = useState("");
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [editingId, setEditingId] = useState<number | null>(null);
    const [editName, setEditName] = useState("");
    const [search, setSearch] = useState("");

    useEffect(() => {
        let active = true;
        apiFetch("/api/admin/brands")
            .then(async (response) => {
                if (!response.ok) throw new Error(await readError(response, "Markalar yüklenemedi."));
                const data = await response.json();
                if (active) setBrands(data);
            })
            .catch((loadError) => { if (active) setError(loadError.message || "Markalar yüklenemedi."); })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, []);

    const addBrand = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!name.trim()) return;
        setSaving(true);
        setError("");
        try {
            const response = await apiFetch("/api/admin/brands", {
                method: "POST",
                body: JSON.stringify({ name: name.trim() }),
            });
            if (!response.ok) throw new Error(await readError(response, "Marka eklenemedi."));
            const created = await response.json();
            setBrands((current) => [...current, created]);
            setName("");
        } catch (requestError) {
            setError((requestError as Error).message || "Sunucuya ulaşılamadı.");
        } finally {
            setSaving(false);
        }
    };

    const saveBrand = async (id: number) => {
        if (!editName.trim()) {
            setError("Marka adı boş olamaz.");
            return;
        }
        setError("");
        try {
            const response = await apiFetch(`/api/admin/brands/${id}`, {
                method: "PUT",
                body: JSON.stringify({ name: editName.trim() }),
            });
            if (!response.ok) throw new Error(await readError(response, "Marka güncellenemedi."));
            const updated = await response.json();
            setBrands((current) => current.map((brand) => brand.id === id ? updated : brand));
            setEditingId(null);
        } catch (requestError) {
            setError((requestError as Error).message || "Marka güncellenemedi.");
        }
    };

    const deleteBrand = async (brand: Brand) => {
        if (!window.confirm(`"${brand.name}" markası silinsin mi?`)) return;
        setError("");
        try {
            const response = await apiFetch(`/api/admin/brands/${brand.id}`, { method: "DELETE" });
            if (!response.ok) throw new Error(await readError(response, "Marka silinemedi. Bu markaya bağlı ürünler olabilir."));
            setBrands((current) => current.filter((item) => item.id !== brand.id));
        } catch (requestError) {
            setError((requestError as Error).message || "Marka silinemedi.");
        }
    };

    const visibleBrands = brands.filter((brand) => brand.name.toLowerCase().includes(search.toLowerCase()));

    return (
        <>
            <div className="page-heading">
                <div>
                    <div className="eyebrow">KATALOG YÖNETİMİ</div>
                    <h1 className="page-title">Markalar</h1>
                    <p className="page-subtitle">Mağazanızda yer alan markaları tek bir yerden düzenleyin.</p>
                </div>
                <span className="badge badge-gray">{brands.length} marka</span>
            </div>

            <section className="category-layout">
                <div className="surface surface-pad category-create">
                    <div className="section-title">Yeni marka ekle</div>
                    <p className="section-caption">Marka adı katalogda ürünlerle birlikte görünür.</p>
                    <form onSubmit={addBrand} style={{ display: "grid", gap: 14, marginTop: 22 }}>
                        <label className="field">
                            <span className="field-label">Marka adı</span>
                            <input className="input" placeholder="Örn. Sarıkaya" value={name} onChange={(event) => setName(event.target.value)} required maxLength={100} />
                        </label>
                        {error && <p className="error-banner" role="alert">{error}</p>}
                        <button className="button button-primary" disabled={saving} type="submit">
                            {saving ? "Ekleniyor…" : "+ Marka ekle"}
                        </button>
                    </form>
                    <div className="category-stat">✧ <span>Toplam <strong>{brands.length}</strong> marka kayıtlı.</span></div>
                </div>

                <section className="surface surface-pad">
                    <div className="toolbar">
                        <div>
                            <h2 className="section-title">Marka listesi</h2>
                            <p className="section-caption">Adına göre arayın, düzenleyin veya kaldırın.</p>
                        </div>
                        <div className="search-field">
                            <span className="search-glyph">⌕</span>
                            <input className="input" placeholder="Marka ara..." value={search} onChange={(event) => setSearch(event.target.value)} />
                        </div>
                    </div>
                    {error && <p className="error-banner" role="alert">{error}</p>}
                    {loading ? <div className="loading-state">Markalar yükleniyor…</div> : visibleBrands.length === 0 ? (
                        <div className="empty-state">
                            <div className="empty-icon">✧</div>
                            <h3>{search ? "Eşleşen marka bulunamadı" : "Henüz marka eklenmemiş"}</h3>
                            <p>{search ? "Arama kelimenizi değiştirmeyi deneyin." : "İlk markanızı soldaki formdan oluşturabilirsiniz."}</p>
                        </div>
                    ) : (
                        <div className="table-wrap">
                            <table className="data-table">
                                <thead><tr><th>Marka</th><th>Kayıt no</th><th style={{ textAlign: "right" }}>İşlemler</th></tr></thead>
                                <tbody>
                                    {visibleBrands.map((brand) => (
                                        <tr key={brand.id}>
                                            <td>
                                                {editingId === brand.id ? (
                                                    <input className="input" autoFocus value={editName} onChange={(event) => setEditName(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") saveBrand(brand.id); if (event.key === "Escape") setEditingId(null); }} />
                                                ) : <span className="cell-primary">{brand.name}</span>}
                                            </td>
                                            <td><span className="cell-sub">#{brand.id}</span></td>
                                            <td>
                                                <div className="button-row" style={{ justifyContent: "flex-end" }}>
                                                    {editingId === brand.id ? (
                                                        <>
                                                            <button className="button button-primary button-small" onClick={() => saveBrand(brand.id)}>Kaydet</button>
                                                            <button className="button button-quiet button-small" onClick={() => setEditingId(null)}>Vazgeç</button>
                                                        </>
                                                    ) : (
                                                        <>
                                                            <button className="button button-quiet button-small" onClick={() => { setEditingId(brand.id); setEditName(brand.name); }}>Düzenle</button>
                                                            <button className="button button-danger button-small" onClick={() => deleteBrand(brand)}>Sil</button>
                                                        </>
                                                    )}
                                                </div>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </section>
            </section>
        </>
    );
}
