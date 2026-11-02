import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch } from "../api";

const UNITS = [
    { value: "PIECE", label: "Adet" },
    { value: "KILOGRAM", label: "Kilogram" },
    { value: "GRAM", label: "Gram" },
    { value: "LITER", label: "Litre" },
    { value: "METER", label: "Metre" },
];

const emptyForm = () => ({
    name: "",
    brandId: "",
    categoryIds: [],
    quantity: "0",
    price: "",
    description: "",
    heightCm: "",
    unit: "PIECE",
    photoUrl: "",
});

const money = (value) => Number(value).toLocaleString("tr-TR", {
    style: "currency",
    currency: "TRY",
});

const readError = async (response, fallback) => {
    const body = await response.json().catch(() => null);
    return body?.message || fallback;
};

export default function AdminProducts() {
    const [products, setProducts] = useState([]);
    const [brands, setBrands] = useState([]);
    const [categories, setCategories] = useState([]);
    const [form, setForm] = useState(emptyForm);
    const [editingId, setEditingId] = useState(null);
    const [error, setError] = useState("");
    const [loadErrors, setLoadErrors] = useState({});
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        let active = true;

        const loadCollection = async (key, path, fallback, setData) => {
            try {
                const response = await apiFetch(path);
                if (!response.ok) throw new Error(await readError(response, fallback));
                const data = await response.json();
                if (!Array.isArray(data)) throw new Error(fallback);
                if (!active) return;
                setData(data);
                setLoadErrors((current) => ({ ...current, [key]: "" }));
            } catch (loadError) {
                if (active) {
                    setLoadErrors((current) => ({
                        ...current,
                        [key]: loadError.message || "Sunucuya ulaşılamadı.",
                    }));
                }
            } finally {
                if (active && key === "products") setLoading(false);
            }
        };

        loadCollection("products", "/api/admin/products", "Ürünler yüklenemedi.", setProducts);
        loadCollection("brands", "/api/admin/brands", "Markalar yüklenemedi.", setBrands);
        loadCollection("categories", "/api/admin/categories", "Kategoriler yüklenemedi.", setCategories);

        return () => {
            active = false;
        };
    }, []);

    const updateField = (field, value) => {
        setForm((current) => ({ ...current, [field]: value }));
    };

    const resetForm = () => {
        setForm(emptyForm());
        setEditingId(null);
    };

    const handleSubmit = async (event) => {
        event.preventDefault();
        setError("");

        const payload = {
            name: form.name.trim(),
            brandId: form.brandId ? Number(form.brandId) : null,
            categoryIds: form.categoryIds.map(Number),
            quantity: Number(form.quantity),
            price: Number(form.price),
            description: form.description.trim() || null,
            heightCm: form.heightCm === "" ? null : Number(form.heightCm),
            unit: form.unit,
            photoUrl: form.photoUrl.trim() || null,
        };

        setSaving(true);
        try {
            const response = await apiFetch(
                editingId === null
                    ? "/api/admin/products"
                    : `/api/admin/products/${editingId}`,
                {
                    method: editingId === null ? "POST" : "PUT",
                    body: JSON.stringify(payload),
                }
            );

            if (!response.ok) {
                setError(await readError(response, "Ürün kaydedilemedi."));
                return;
            }

            const savedProduct = await response.json();
            setProducts((current) => editingId === null
                ? [...current, savedProduct]
                : current.map((product) => product.id === editingId ? savedProduct : product));
            resetForm();
        } catch (saveError) {
            setError(saveError.message || "Sunucuya ulaşılamadı.");
        } finally {
            setSaving(false);
        }
    };

    const startEdit = (product) => {
        setError("");
        setEditingId(product.id);
        setForm({
            name: product.name ?? "",
            brandId: product.brandId == null ? "" : String(product.brandId),
            categoryIds: (product.categoryIds ?? []).map(String),
            quantity: String(product.quantity ?? 0),
            price: product.price == null ? "" : String(product.price),
            description: product.description ?? "",
            heightCm: product.heightCm == null ? "" : String(product.heightCm),
            unit: product.unit ?? "PIECE",
            photoUrl: product.photoUrl ?? "",
        });
        window.scrollTo({ top: 0, behavior: "smooth" });
    };

    const handleDelete = async (product) => {
        if (!window.confirm(`"${product.name}" ürünü silinsin mi?`)) return;
        setError("");

        try {
            const response = await apiFetch(`/api/admin/products/${product.id}`, {
                method: "DELETE",
            });
            if (!response.ok) {
                setError(await readError(response, "Ürün silinemedi."));
                return;
            }

            setProducts((current) => current.filter((item) => item.id !== product.id));
            if (editingId === product.id) resetForm();
        } catch (deleteError) {
            setError(deleteError.message || "Sunucuya ulaşılamadı.");
        }
    };

    return (
        <div style={styles.page}>
            <Link to="/admin">← Admin Paneli</Link>

            <section style={styles.card}>
                <h2 style={styles.heading}>{editingId === null ? "Ürün ekle" : "Ürünü düzenle"}</h2>

                <form onSubmit={handleSubmit} style={styles.form}>
                    <label style={styles.field}>
                        <span>Ürün adı *</span>
                        <input
                            style={styles.input}
                            required
                            maxLength={255}
                            value={form.name}
                            onChange={(event) => updateField("name", event.target.value)}
                        />
                    </label>

                    <label style={styles.field}>
                        <span>Marka</span>
                        <select
                            style={styles.input}
                            value={form.brandId}
                            onChange={(event) => updateField("brandId", event.target.value)}
                        >
                            <option value="">Marka seçilmedi</option>
                            {loadErrors.brands && <option value="" disabled>{loadErrors.brands}</option>}
                            {!loadErrors.brands && brands.length === 0 && (
                                <option value="" disabled>Henüz marka eklenmemiş</option>
                            )}
                            {brands.map((brand) => (
                                <option key={brand.id} value={String(brand.id)}>{brand.name}</option>
                            ))}
                        </select>
                        {loadErrors.brands && <small role="alert" style={styles.error}>{loadErrors.brands}</small>}
                    </label>

                    <label style={styles.field}>
                        <span>Kategoriler</span>
                        <select
                            style={{ ...styles.input, minHeight: 120 }}
                            multiple
                            value={form.categoryIds}
                            onChange={(event) => updateField(
                                "categoryIds",
                                Array.from(event.target.selectedOptions, (option) => option.value)
                            )}
                        >
                            {loadErrors.categories && (
                                <option value="" disabled>{loadErrors.categories}</option>
                            )}
                            {!loadErrors.categories && categories.length === 0 && (
                                <option value="" disabled>Henüz kategori eklenmemiş</option>
                            )}
                            {categories.map((category) => (
                                <option key={category.id} value={String(category.id)}>
                                    {category.name}
                                </option>
                            ))}
                        </select>
                        {loadErrors.categories && (
                            <small role="alert" style={styles.error}>{loadErrors.categories}</small>
                        )}
                        <small>Birden fazla kategori seçmek için Ctrl tuşunu basılı tutun.</small>
                    </label>

                    <div style={styles.row}>
                        <label style={styles.field}>
                            <span>Fiyat (TL) *</span>
                            <input
                                style={styles.input}
                                type="number"
                                required
                                min="0"
                                step="0.01"
                                value={form.price}
                                onChange={(event) => updateField("price", event.target.value)}
                            />
                        </label>
                        <label style={styles.field}>
                            <span>Stok miktarı *</span>
                            <input
                                style={styles.input}
                                type="number"
                                required
                                min="0"
                                step="1"
                                value={form.quantity}
                                onChange={(event) => updateField("quantity", event.target.value)}
                            />
                        </label>
                        <label style={styles.field}>
                            <span>Satış birimi *</span>
                            <select
                                style={styles.input}
                                required
                                value={form.unit}
                                onChange={(event) => updateField("unit", event.target.value)}
                            >
                                {UNITS.map((unit) => (
                                    <option key={unit.value} value={unit.value}>{unit.label}</option>
                                ))}
                            </select>
                        </label>
                    </div>

                    <div style={styles.row}>
                        <label style={styles.field}>
                            <span>Yükseklik (cm)</span>
                            <input
                                style={styles.input}
                                type="number"
                                min="0"
                                step="0.01"
                                value={form.heightCm}
                                onChange={(event) => updateField("heightCm", event.target.value)}
                            />
                        </label>
                        <label style={{ ...styles.field, flex: 2 }}>
                            <span>Fotoğraf URL'si</span>
                            <input
                                style={styles.input}
                                type="url"
                                maxLength={1000}
                                value={form.photoUrl}
                                onChange={(event) => updateField("photoUrl", event.target.value)}
                            />
                        </label>
                    </div>

                    <label style={styles.field}>
                        <span>Açıklama</span>
                        <textarea
                            style={{ ...styles.input, minHeight: 90, resize: "vertical" }}
                            value={form.description}
                            onChange={(event) => updateField("description", event.target.value)}
                        />
                    </label>

                    {error && <p role="alert" style={styles.error}>{error}</p>}

                    <div style={styles.actions}>
                        <button style={styles.save} type="submit" disabled={saving}>
                            {saving ? "Kaydediliyor..." : editingId === null ? "Ürün ekle" : "Değişiklikleri kaydet"}
                        </button>
                        {editingId !== null && (
                            <button style={styles.cancel} type="button" onClick={resetForm}>
                                Düzenlemeyi iptal et
                            </button>
                        )}
                    </div>
                </form>
            </section>

            <section style={styles.card}>
                <h2 style={styles.heading}>Ürünler</h2>
                {loading ? (
                    <p>Ürünler yükleniyor...</p>
                ) : loadErrors.products ? (
                    <p role="alert" style={styles.error}>{loadErrors.products}</p>
                ) : products.length === 0 ? (
                    <p>Henüz ürün yok.</p>
                ) : (
                    <div style={styles.tableWrap}>
                        <table style={styles.table}>
                            <thead>
                            <tr>
                                <th style={styles.th}>Ürün</th>
                                <th style={styles.th}>Marka</th>
                                <th style={styles.th}>Kategoriler</th>
                                <th style={styles.th}>Fiyat</th>
                                <th style={styles.th}>Stok</th>
                                <th style={styles.th}>Birim</th>
                                <th style={styles.th}>İşlemler</th>
                            </tr>
                            </thead>
                            <tbody>
                            {products.map((product) => (
                                <tr key={product.id}>
                                    <td style={styles.td}>
                                        <div style={styles.productCell}>
                                            {product.photoUrl && (
                                                <img
                                                    src={product.photoUrl}
                                                    alt=""
                                                    style={styles.thumbnail}
                                                    onError={(event) => { event.currentTarget.hidden = true; }}
                                                />
                                            )}
                                            <span>{product.name}</span>
                                        </div>
                                    </td>
                                    <td style={styles.td}>{product.brandName || "-"}</td>
                                    <td style={styles.td}>{product.categoryNames?.join(", ") || "-"}</td>
                                    <td style={styles.td}>{product.price == null ? "-" : money(product.price)}</td>
                                    <td style={styles.td}>{product.quantity ?? 0}</td>
                                    <td style={styles.td}>
                                        {UNITS.find((unit) => unit.value === product.unit)?.label || product.unit || "-"}
                                    </td>
                                    <td style={{ ...styles.td, whiteSpace: "nowrap" }}>
                                        <button style={styles.edit} type="button" onClick={() => startEdit(product)}>
                                            Düzenle
                                        </button>
                                        <button style={styles.delete} type="button" onClick={() => handleDelete(product)}>
                                            Sil
                                        </button>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>
        </div>
    );
}

const button = {
    padding: "7px 12px",
    border: "none",
    borderRadius: 4,
    cursor: "pointer",
    marginRight: 6,
    color: "#fff",
};

const styles = {
    page: { maxWidth: 1100, margin: "0 auto", padding: 24, fontFamily: "system-ui, sans-serif" },
    card: { marginTop: 16, padding: 20, border: "1px solid #ddd", borderRadius: 8 },
    heading: { marginTop: 0 },
    form: { display: "flex", flexDirection: "column", gap: 14 },
    field: { display: "flex", flex: 1, flexDirection: "column", gap: 6, minWidth: 0 },
    input: { width: "100%", boxSizing: "border-box", padding: 9, fontSize: 15, border: "1px solid #bbb", borderRadius: 4 },
    row: { display: "flex", gap: 12, flexWrap: "wrap" },
    actions: { display: "flex", gap: 8 },
    save: { ...button, background: "#198754" },
    cancel: { ...button, background: "#6c757d" },
    edit: { ...button, background: "#6c757d" },
    delete: { ...button, background: "#dc3545", marginRight: 0 },
    error: { margin: 0, color: "#dc3545" },
    tableWrap: { overflowX: "auto" },
    table: { width: "100%", borderCollapse: "collapse" },
    th: { textAlign: "left", padding: 8, borderBottom: "2px solid #ddd", whiteSpace: "nowrap" },
    td: { padding: 8, borderBottom: "1px solid #eee", verticalAlign: "middle" },
    productCell: { display: "flex", alignItems: "center", gap: 8, minWidth: 160 },
    thumbnail: { width: 42, height: 42, objectFit: "cover", borderRadius: 4 },
};
