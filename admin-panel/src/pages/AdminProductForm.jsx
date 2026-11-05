import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { apiFetch } from "../api";
import CategoryPicker from "../components/CategoryPicker";

const blankForm = () => ({
    name: "", categoryIds: [], price: "", stock: "0", description: "", imageUrl: "", origin: "", brandId: "",
});

const readError = async (response, fallback) => {
    const body = await response.json().catch(() => null);
    return body?.message || fallback;
};

const applyProduct = (product) => ({
    name: product.name ?? "",
    categoryIds: (product.categoryIds ?? []).map(String),
    price: product.price == null ? "" : String(product.price),
    stock: String(product.stock ?? 0),
    description: product.description ?? "",
    imageUrl: product.imageUrl ?? "",
    origin: product.origin ?? "",
    brandId: product.brandId == null ? "" : String(product.brandId),
});

export default function AdminProductForm() {
    const { id } = useParams();
    const navigate = useNavigate();
    const isEditing = Boolean(id);
    const [form, setForm] = useState(blankForm);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(isEditing);
    const [saving, setSaving] = useState(false);
    const [categories, setCategories] = useState([]);
    const [pickerOpen, setPickerOpen] = useState(false);
    const [brands, setBrands] = useState([]);

    useEffect(() => {
        let active = true;
        const load = async () => {
            try {
                const categoryResponse = await apiFetch("/api/admin/categories");
                if (categoryResponse.ok) {
                    const categoryData = await categoryResponse.json();
                    if (active) setCategories(categoryData);
                }
                const brandResponse = await apiFetch("/api/admin/brands");
                if (brandResponse.ok) {
                    const brandData = await brandResponse.json();
                    if (active) setBrands(brandData);
                }
                if (!isEditing) return;
                const response = await apiFetch(`/api/products/${id}`);
                if (!response.ok) throw new Error(await readError(response, "Ürün bilgileri yüklenemedi."));
                const product = await response.json();
                if (!active) return;
                setForm(applyProduct(product));
            } catch (loadError) {
                if (active) setError(loadError.message || "Form verileri yüklenemedi.");
            } finally {
                if (active) setLoading(false);
            }
        };
        load();
        return () => { active = false; };
    }, [id, isEditing]);

    const updateField = (field, value) => setForm((current) => ({ ...current, [field]: value }));

    const removeCategory = (categoryId) =>
        updateField("categoryIds", form.categoryIds.filter((value) => value !== categoryId));

    const submit = async (event) => {
        event.preventDefault();
        setError("");
        const payload = {
            name: form.name.trim(),
            categoryIds: form.categoryIds.map(Number),
            description: form.description.trim() || null,
            price: Number(form.price),
            stock: Number(form.stock),
            imageUrl: form.imageUrl.trim() || null,
            origin: form.origin.trim() || null,
            brandId: form.brandId ? Number(form.brandId) : null,
        };
        setSaving(true);
        try {
            const response = await apiFetch(isEditing ? `/api/admin/products/${id}` : "/api/admin/products", {
                method: isEditing ? "PUT" : "POST",
                body: JSON.stringify(payload),
            });
            if (!response.ok) throw new Error(await readError(response, "Ürün kaydedilemedi."));
            navigate("/admin/products");
        } catch (saveError) {
            setError(saveError.message || "Sunucuya ulaşılamadı.");
        } finally {
            setSaving(false);
        }
    };

    if (loading) return <div className="loading-state">Ürün bilgileri yükleniyor…</div>;

    return (
        <>
            <div className="page-heading">
                <div>
                    <div className="eyebrow">KATALOG YÖNETİMİ</div>
                    <h1 className="page-title">{isEditing ? "Ürünü düzenle" : "Yeni ürün"}</h1>
                    <p className="page-subtitle">Ürün bilgilerini ekleyin, sınıflandırın ve mağazanıza yayınlayın.</p>
                </div>
                <Link className="button button-quiet" to="/admin/products">← Ürün listesine dön</Link>
            </div>

            {pickerOpen && (
                <CategoryPicker
                    categories={categories}
                    selectedIds={form.categoryIds}
                    onChange={(ids) => updateField("categoryIds", ids)}
                    onClose={() => setPickerOpen(false)}
                />
            )}
            <form className="surface surface-pad" onSubmit={submit}>
                {error && <p className="error-banner" role="alert">{error}</p>}
                <div className="product-form-grid">
                    <div className="form-section-title">Temel bilgiler</div>
                    <label className="field span-2">
                        <span className="field-label">Ürün adı *</span>
                        <input className="input" required maxLength={150} value={form.name} onChange={(event) => updateField("name", event.target.value)} placeholder="Örn. Organik elma" />
                    </label>
                    <label className="field span-2">
                        <span className="field-label">Fotoğraf URL'si</span>
                        <input className="input" type="url" maxLength={500} value={form.imageUrl} onChange={(event) => updateField("imageUrl", event.target.value)} placeholder="https://..." />
                    </label>
                    <label className="field span-2">
                        <span className="field-label">Marka</span>
                        <select className="input" value={form.brandId} onChange={(event) => updateField("brandId", event.target.value)}>
                            <option value="">Marka seçilmedi</option>
                            {brands.map((brand) => <option key={brand.id} value={brand.id}>{brand.name}</option>)}
                        </select>
                    </label>
                    <label className="field span-2">
                        <span className="field-label">Üretim yeri</span>
                        <input className="input" maxLength={100} value={form.origin} onChange={(event) => updateField("origin", event.target.value)} placeholder="Örn. Türkiye" />
                    </label>

                    <div className="field span-2">
                        <span className="field-label">Kategoriler <span style={{ color: "var(--muted)", fontWeight: 400 }}>· {form.categoryIds.length} seçildi</span></span>
                        <div className="category-select-box">
                            <div className="category-chips">
                                {form.categoryIds.length ? form.categoryIds.map((categoryId) => (
                                    <span className="category-chip" key={categoryId}>
                                        {categories.find((category) => String(category.id) === categoryId)?.name ?? categoryId}
                                        <button type="button" aria-label="Kaldır" onClick={() => removeCategory(categoryId)}>✕</button>
                                    </span>
                                )) : <span className="section-caption">Henüz kategori seçilmedi.</span>}
                            </div>
                            <button type="button" className="button button-quiet button-small" onClick={() => setPickerOpen(true)}>
                                Kategori seç
                            </button>
                        </div>
                    </div>

                    <div className="form-section-title">Fiyat ve stok</div>
                    <label className="field">
                        <span className="field-label">Fiyat (TL) *</span>
                        <input className="input" type="number" min="0" step="0.01" required value={form.price} onChange={(event) => updateField("price", event.target.value)} placeholder="0,00" />
                    </label>
                    <label className="field">
                        <span className="field-label">Stok miktarı *</span>
                        <input className="input" type="number" min="0" step="1" required value={form.stock} onChange={(event) => updateField("stock", event.target.value)} />
                    </label>
                    <label className="field span-2">
                        <span className="field-label">Ürün açıklaması</span>
                        <textarea className="textarea" value={form.description} onChange={(event) => updateField("description", event.target.value)} placeholder="Ürününüzü müşterilere tanıtın..." />
                    </label>
                    <div className="span-2 button-row" style={{ justifyContent: "flex-end", marginTop: 6 }}>
                        <Link className="button button-quiet" to="/admin/products">Vazgeç</Link>
                        <button className="button button-primary" type="submit" disabled={saving}>{saving ? "Kaydediliyor…" : isEditing ? "Değişiklikleri kaydet" : "Ürünü oluştur"}</button>
                    </div>
                </div>
            </form>
        </>
    );
}
