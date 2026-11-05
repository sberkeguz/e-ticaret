import { useEffect, useMemo, useState } from "react";
import { apiFetch } from "../api";

const readError = async (response, fallback) => {
    const body = await response.json().catch(() => null);
    return body?.message || fallback;
};

export default function AdminCategory() {
    const [categories, setCategories] = useState([]);
    const [name, setName] = useState("");
    const [parentIds, setParentIds] = useState([]);
    const [search, setSearch] = useState("");
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [editing, setEditing] = useState(null);
    const [editName, setEditName] = useState("");
    const [editParentIds, setEditParentIds] = useState([]);

    useEffect(() => {
        let active = true;
        apiFetch("/api/admin/categories")
            .then(async (response) => {
                if (!response.ok) throw new Error(await readError(response, "Kategoriler yüklenemedi."));
                const data = await response.json();
                if (active) setCategories(data);
            })
            .catch((loadError) => { if (active) setError(loadError.message || "Kategoriler yüklenemedi."); })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, []);

    const filtered = useMemo(() => categories.filter((category) =>
        category.name.toLocaleLowerCase("tr").includes(search.toLocaleLowerCase("tr"))
    ), [categories, search]);

    const categoryName = (id) => categories.find((category) => category.id === id)?.name;
    const isDescendant = (ancestorId, targetId, visited = new Set()) => {
        if (visited.has(ancestorId)) return false;
        visited.add(ancestorId);
        const children = categories.filter((category) => (category.parentIds ?? []).includes(ancestorId));
        return children.some((child) =>
            child.id === targetId || isDescendant(child.id, targetId, visited)
        );
    };

    const addCategory = async (event) => {
        event.preventDefault();
        if (!name.trim()) return;
        setSaving(true);
        setError("");
        try {
            const response = await apiFetch("/api/admin/categories", {
                method: "POST",
                body: JSON.stringify({ name: name.trim(), parentIds: parentIds.map(Number) }),
            });
            if (!response.ok) throw new Error(await readError(response, "Kategori eklenemedi."));
            const created = await response.json();
            setCategories((current) => [...current, created]);
            setName("");
            setParentIds([]);
        } catch (requestError) {
            setError(requestError.message || "Sunucuya ulaşılamadı.");
        } finally {
            setSaving(false);
        }
    };

    const saveCategory = async (event) => {
        event.preventDefault();
        if (!editName.trim()) {
            setError("Kategori adı boş olamaz.");
            return;
        }
        setSaving(true);
        setError("");
        try {
            const response = await apiFetch(`/api/admin/categories/${editing.id}`, {
                method: "PUT",
                body: JSON.stringify({ name: editName.trim(), parentIds: editParentIds.map(Number) }),
            });
            if (!response.ok) throw new Error(await readError(response, "Kategori güncellenemedi."));
            const updated = await response.json();
            setCategories((current) => current.map((category) => category.id === updated.id ? updated : category));
            setEditing(null);
        } catch (requestError) {
            setError(requestError.message || "Kategori güncellenemedi.");
        } finally {
            setSaving(false);
        }
    };

    const deleteCategory = async (category) => {
        if (!window.confirm(`"${category.name}" kategorisi silinsin mi?`)) return;
        setError("");
        try {
            const response = await apiFetch(`/api/admin/categories/${category.id}`, { method: "DELETE" });
            if (!response.ok) throw new Error(await readError(response, "Kategori silinemedi."));
            setCategories((current) => current.filter((item) => item.id !== category.id));
        } catch (requestError) {
            setError(requestError.message || "Kategori silinemedi.");
        }
    };

    return (
        <>
            <div className="page-heading">
                <div>
                    <div className="eyebrow">KATALOG MİMARİSİ</div>
                    <h1 className="page-title">Kategoriler</h1>
                    <p className="page-subtitle">Ürün kataloğunuzu anlaşılır koleksiyonlara ayırın ve düzenli tutun.</p>
                </div>
                <span className="badge badge-gray">{categories.length} kategori</span>
            </div>

            {error && <p className="error-banner" role="alert">{error}</p>}

            <section className="category-layout">
                <div className="surface surface-pad category-create">
                    <div className="stat-icon" style={{ marginBottom: 16 }}>◈</div>
                    <h2 className="section-title">Koleksiyon oluştur</h2>
                    <p className="section-caption">Yeni ürün grupları ekleyerek mağaza kataloğunu zenginleştirin.</p>
                    <form onSubmit={addCategory} style={{ display: "grid", gap: 14, marginTop: 22 }}>
                        <label className="field">
                            <span className="field-label">Kategori adı</span>
                            <input className="input" value={name} onChange={(event) => setName(event.target.value)} placeholder="Örn. Taze meyveler" maxLength={100} required />
                        </label>
                        <div className="field">
                            <span className="field-label">Üst kategoriler <span style={{ color: "var(--muted)", fontWeight: 400 }}>· isteğe bağlı</span></span>
                            {categories.length ? (
                                <div className="product-category-picker" style={{ maxHeight: 180 }}>
                                    {categories.map((category) => (
                                        <label className="category-option" key={category.id}>
                                            <input
                                                type="checkbox"
                                                checked={parentIds.includes(String(category.id))}
                                                onChange={() => setParentIds((current) => current.includes(String(category.id))
                                                    ? current.filter((value) => value !== String(category.id))
                                                    : [...current, String(category.id)])}
                                            />
                                            <span>{category.name}</span>
                                        </label>
                                    ))}
                                </div>
                            ) : <span className="section-caption">İlk kategori üst kategori olmadan oluşturulur.</span>}
                            <span className="section-caption">Birden fazla üst kategori seçebilirsiniz.</span>
                        </div>
                        <button className="button button-primary" type="submit" disabled={saving}>
                            {saving ? "Oluşturuluyor…" : "+ Kategori oluştur"}
                        </button>
                    </form>
                    <div className="category-stat">◉ <span>Bu katalogda <strong>{categories.length}</strong> kategori bulunuyor.</span></div>
                    <p className="section-caption" style={{ lineHeight: 1.7 }}>
                        Kategoriler ürün ekleme ekranında seçilebilir. Her kategori, ürünleri müşteriler için daha kolay keşfedilebilir hale getirir.
                    </p>
                </div>

                <section className="surface surface-pad">
                    <div className="toolbar">
                        <div>
                            <h2 className="section-title">Koleksiyonlarınız</h2>
                            <p className="section-caption">Kategori kartlarını arayın, düzenleyin veya kaldırın.</p>
                        </div>
                        <div className="search-field">
                            <span className="search-glyph">⌕</span>
                            <input className="input" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Kategori ara..." />
                        </div>
                    </div>
                    {loading ? <div className="loading-state">Kategoriler yükleniyor…</div> : filtered.length === 0 ? (
                        <div className="empty-state">
                            <div className="empty-icon">◈</div>
                            <h3>{search ? "Eşleşen kategori yok" : "İlk koleksiyonunuzu oluşturun"}</h3>
                            <p>{search ? "Başka bir arama kelimesi deneyin." : "Ürünlerinizi gruplandırmak için soldaki formu kullanın."}</p>
                        </div>
                    ) : (
                        <div className="category-grid">
                            {filtered.map((category, index) => (
                                <article className="surface category-card" key={category.id}>
                                    <div className="category-card-top">
                                        <span className="category-symbol">{["◈", "◉", "◇", "▤", "✦"][index % 5]}</span>
                                        <div className="category-actions">
                                            <button className="button button-quiet button-small button-icon" title="Düzenle" aria-label={`${category.name} kategorisini düzenle`} onClick={() => { setError(""); setEditing(category); setEditName(category.name); setEditParentIds((category.parentIds ?? []).map(String)); }}>✎</button>
                                            <button className="button button-danger button-small button-icon" title="Sil" aria-label={`${category.name} kategorisini sil`} onClick={() => deleteCategory(category)}>×</button>
                                        </div>
                                    </div>
                                    <h3 className="category-name">{category.name}</h3>
                                    <div className="category-id">KATEGORİ · #{category.id}</div>
                                    <div className="category-stat">
                                        ⌁ <span>Üst kategoriler: {(category.parentIds ?? []).map(categoryName).filter(Boolean).join(", ") || "Ana kategori"}</span>
                                    </div>
                                </article>
                            ))}
                        </div>
                    )}
                </section>
            </section>

            {editing && (
                <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setEditing(null); }}>
                    <form className="modal-card" onSubmit={saveCategory}>
                        <div className="modal-head">
                            <div><h2>Kategoriyi düzenle</h2><p>Kategori adı tüm katalogda güncellenir.</p></div>
                            <button className="button button-quiet button-icon" type="button" aria-label="Kapat" onClick={() => setEditing(null)}>×</button>
                        </div>
                        <div className="modal-body" style={{ display: "grid", gap: 16 }}>
                            <label className="field">
                                <span className="field-label">Kategori adı</span>
                                <input className="input" autoFocus value={editName} onChange={(event) => setEditName(event.target.value)} maxLength={100} />
                            </label>
                            <div className="field">
                                <span className="field-label">Üst kategoriler</span>
                                {categories.filter((category) =>
                                    category.id !== editing.id && !isDescendant(editing.id, category.id)
                                ).length ? (
                                    <div className="product-category-picker">
                                        {categories
                                            .filter((category) => category.id !== editing.id && !isDescendant(editing.id, category.id))
                                            .map((category) => (
                                                <label className="category-option" key={category.id}>
                                                    <input
                                                        type="checkbox"
                                                        checked={editParentIds.includes(String(category.id))}
                                                        onChange={() => setEditParentIds((current) => current.includes(String(category.id))
                                                            ? current.filter((value) => value !== String(category.id))
                                                            : [...current, String(category.id)])}
                                                    />
                                                    <span>{category.name}</span>
                                                </label>
                                            ))}
                                    </div>
                                ) : <span className="section-caption">Bu kategori ana kategori olarak kalacak.</span>}
                                <span className="section-caption">Birden fazla üst kategori seçebilirsiniz.</span>
                            </div>
                        </div>
                        <div className="modal-actions">
                            <button className="button button-quiet" type="button" onClick={() => setEditing(null)}>Vazgeç</button>
                            <button className="button button-primary" type="submit" disabled={saving}>{saving ? "Kaydediliyor…" : "Değişiklikleri kaydet"}</button>
                        </div>
                    </form>
                </div>
            )}
        </>
    );
}
