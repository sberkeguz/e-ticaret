import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch } from "../api";

export default function AdminBrand() {
    const [brands, setBrands] = useState([]);
    const [name, setName] = useState("");
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);

    // Düzenleme durumu: hangi markanın düzenlendiği ve input'taki geçici isim
    const [editingId, setEditingId] = useState(null);
    const [editName, setEditName] = useState("");

    // Sayfa açılınca markaları getir
    const loadBrands = async () => {
        try {
            const res = await apiFetch("/api/admin/brands");
            if (!res.ok) throw new Error();
            setBrands(await res.json());
        } catch {
            setError("Markalar yüklenemedi.");
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        loadBrands();
    }, []);

    // Marka ekle
    const handleAdd = async (e) => {
        e.preventDefault();
        setError("");
        if (!name.trim()) return;

        try {
            const res = await apiFetch("/api/admin/brands", {
                method: "POST",
                body: JSON.stringify({ name: name.trim() }),
            });
            if (!res.ok) throw new Error();
            const saved = await res.json();
            setBrands([...brands, saved]);
            setName("");
        } catch {
            setError("Marka eklenemedi.");
        }
    };

    // Düzenlemeyi başlat: satırı input moduna al
    const startEdit = (brand) => {
        setError("");
        setEditingId(brand.id);
        setEditName(brand.name);
    };

    // Düzenlemeyi iptal et
    const cancelEdit = () => {
        setEditingId(null);
        setEditName("");
    };

    // Marka güncelle: PUT /api/admin/brands/{id}
    const handleUpdate = async (id) => {
        setError("");
        if (!editName.trim()) {
            setError("Marka adı boş olamaz.");
            return;
        }

        try {
            const res = await apiFetch(`/api/admin/brands/${id}`, {
                method: "PUT",
                body: JSON.stringify({ name: editName.trim() }),
            });
            if (!res.ok) throw new Error();
            const updated = await res.json();
            // Listede sadece güncellenen markayı değiştir
            setBrands(brands.map((b) => (b.id === id ? updated : b)));
            cancelEdit();
        } catch {
            setError("Marka güncellenemedi.");
        }
    };

    // Marka sil
    const handleDelete = async (id, brandName) => {
        if (!window.confirm(`"${brandName}" markası silinsin mi?`)) return;
        setError("");

        try {
            const res = await apiFetch(`/api/admin/brands/${id}`, { method: "DELETE" });
            if (!res.ok) throw new Error();
            setBrands(brands.filter((b) => b.id !== id));
            if (editingId === id) cancelEdit();
        } catch {
            setError("Marka silinemedi. Bu markaya bağlı ürünler olabilir.");
        }
    };

    return (
        <div style={styles.page}>
            <Link to="/admin">← Admin Paneli</Link>

            <section style={styles.card}>
                <h2 style={{ marginTop: 0 }}>Markalar</h2>

                <form onSubmit={handleAdd} style={styles.form}>
                    <input
                        style={styles.input}
                        placeholder="Marka adı"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                    />
                    <button style={styles.add} type="submit">Ekle</button>
                </form>

                {error && <p style={styles.error}>{error}</p>}

                {loading ? (
                    <p>Yükleniyor...</p>
                ) : brands.length === 0 ? (
                    <p>Henüz marka yok.</p>
                ) : (
                    <table style={styles.table}>
                        <thead>
                        <tr>
                            <th style={styles.th}>ID</th>
                            <th style={styles.th}>Marka</th>
                            <th style={styles.th}></th>
                        </tr>
                        </thead>
                        <tbody>
                        {brands.map((b) => (
                            <tr key={b.id}>
                                <td style={styles.td}>{b.id}</td>

                                {/* Düzenleme modundaysa input, değilse düz metin */}
                                <td style={styles.td}>
                                    {editingId === b.id ? (
                                        <input
                                            style={styles.editInput}
                                            value={editName}
                                            autoFocus
                                            onChange={(e) => setEditName(e.target.value)}
                                            onKeyDown={(e) => {
                                                if (e.key === "Enter") handleUpdate(b.id);
                                                if (e.key === "Escape") cancelEdit();
                                            }}
                                        />
                                    ) : (
                                        b.name
                                    )}
                                </td>

                                <td style={{ ...styles.td, textAlign: "right" }}>
                                    {editingId === b.id ? (
                                        <>
                                            <button style={styles.save} onClick={() => handleUpdate(b.id)}>
                                                Kaydet
                                            </button>
                                            <button style={styles.cancel} onClick={cancelEdit}>
                                                İptal
                                            </button>
                                        </>
                                    ) : (
                                        <>
                                            <button style={styles.edit} onClick={() => startEdit(b)}>
                                                Düzenle
                                            </button>
                                            <button style={styles.delete} onClick={() => handleDelete(b.id, b.name)}>
                                                Sil
                                            </button>
                                        </>
                                    )}
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                )}
            </section>
        </div>
    );
}

const btn = { padding: "6px 12px", border: "none", borderRadius: 4, cursor: "pointer", marginLeft: 6, color: "#fff" };

const styles = {
    page: { maxWidth: 800, margin: "0 auto", padding: 24, fontFamily: "system-ui, sans-serif" },
    card: { marginTop: 16, padding: 20, border: "1px solid #ddd", borderRadius: 8 },
    form: { display: "flex", gap: 8, marginBottom: 16 },
    input: { flex: 1, padding: 10, fontSize: 16, border: "1px solid #bbb", borderRadius: 4 },
    editInput: { width: "100%", padding: 6, fontSize: 16, border: "1px solid #0d6efd", borderRadius: 4, boxSizing: "border-box" },
    add: { padding: "10px 20px", background: "#0d6efd", color: "#fff", border: "none", borderRadius: 4, cursor: "pointer" },
    edit: { ...btn, background: "#6c757d" },
    save: { ...btn, background: "#198754" },
    cancel: { ...btn, background: "#6c757d" },
    delete: { ...btn, background: "#dc3545" },
    error: { color: "#dc3545" },
    table: { width: "100%", borderCollapse: "collapse" },
    th: { textAlign: "left", padding: 8, borderBottom: "2px solid #ddd" },
    td: { padding: 8, borderBottom: "1px solid #eee" },
};