import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch } from "../api";


export default function AdminCategory() {
    const [categories, setCategories] = useState([]);
    const [name, setName] = useState("");
    // Kategori birden fazla üst kategoriye bağlanabilir.
    const [parentIds, setParentIds] = useState([]);
    
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);

    // Düzenleme durumu
    const [editingId, setEditingId] = useState(null);
    const [editName, setEditName] = useState("");
    const [editParentIds, setEditParentIds] = useState([]); // Düzenlenen kategorinin üst kategorileri

    // Sunucunun gönderdiği "message" alanını okur
    const readError = async (res, fallback) => {
        const err = await res.json().catch(() => null);
        return err?.message || fallback;
    };

    useEffect(() => {
        const load = async () => {
            try {
                const res = await apiFetch("/api/admin/categories");
                if (!res.ok) throw new Error();
                setCategories(await res.json());
            } catch {
                setError("Kategoriler yüklenemedi.");
            } finally {
                setLoading(false);
            }
        };
        load();
    }, []);

    // Ekle: POST
    const handleAdd = async (e) => {
        e.preventDefault();
        setError("");
        if (!name.trim()) return;

        try {
            const res = await apiFetch("/api/admin/categories", {
                method: "POST",
                // Seçilen tüm üst kategori ID'lerini backend'e parentIds listesi olarak gönder.
                body: JSON.stringify({
                    name: name.trim(),
                    parentIds: parentIds.map(Number)
                }),
            });
            if (!res.ok) {
                setError(await readError(res, "Kategori eklenemedi. Bu isim zaten kayıtlı olabilir."));
                return;
            }
            const saved = await res.json();
            setCategories((current) => [...current, saved]);
            setName("");
            setParentIds([]); // Başarıyla eklenince üst kategori seçimini temizle
        } catch {
            setError("Sunucuya ulaşılamadı.");
        }
    };

    const startEdit = (c) => {
        setError("");
        setEditingId(c.id);
        setEditName(c.name);
        // API'den gelen sayısal ID'leri select bileşeninin string değerlerine çevir.
        setEditParentIds((c.parentIds ?? []).map(String));
    };

    const cancelEdit = () => {
        setEditingId(null);
        setEditName("");
        setEditParentIds([]);
    };

    // Güncelle: PUT
    const handleUpdate = async (id) => {
        setError("");
        if (!editName.trim()) {
            setError("Kategori adı boş olamaz.");
            return;
        }
        try {
            const res = await apiFetch(`/api/admin/categories/${id}`, {
                method: "PUT",
                // Güncellemede seçilmiş üst kategorilerin tamamını gönder.
                body: JSON.stringify({
                    name: editName.trim(),
                    parentIds: editParentIds.map(Number)
                }),
            });
            if (!res.ok) {
                setError(await readError(res, "Kategori güncellenemedi."));
                return;
            }
            const updated = await res.json();
            setCategories((current) => current.map((c) => (c.id === id ? updated : c)));
            cancelEdit();
        } catch {
            setError("Sunucuya ulaşılamadı.");
        }
    };

    // Sil: DELETE
    const handleDelete = async (id, categoryName) => {
        if (!window.confirm(`"${categoryName}" kategorisi silinsin mi?`)) return;
        setError("");
        try {
            const res = await apiFetch(`/api/admin/categories/${id}`, { method: "DELETE" });
            if (!res.ok) {
                setError(await readError(res, "Kategori silinemedi. Bu kategoriye bağlı alt kategoriler veya ürünler olabilir."));
                return;
            }
            setCategories(categories.filter((c) => c.id !== id));
            if (editingId === id) cancelEdit();
        } catch {
            setError("Sunucuya ulaşılamadı.");
        }
    };

    return (
        <div style={styles.page}>
            <Link to="/admin">← Admin Paneli</Link>

            <section style={styles.card}>
                <h2 style={{ marginTop: 0 }}>Kategoriler</h2>

                <form onSubmit={handleAdd} style={styles.form}>
                    <input
                        style={styles.input}
                        placeholder="Kategori adı"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                    />
                    
                    {/* Ctrl tuşuyla birden fazla üst kategori seçilebilir. */}
                    <select
                        style={styles.input}
                        multiple
                        size={4}
                        aria-label="Üst kategoriler"
                        value={parentIds}
                        onChange={(e) =>
                            setParentIds(Array.from(e.target.selectedOptions, (option) => option.value))
                        }
                    >
                        {categories.map((c) => (
                            <option key={c.id} value={String(c.id)}>{c.name}</option>
                        ))}
                    </select>

                    <button style={styles.add} type="submit">Ekle</button>
                </form>
                <small>Birden fazla üst kategori seçmek için Ctrl tuşuna basılı tutun. Seçim yapmazsanız kategori kök olur.</small>

                {error && <p style={styles.error}>{error}</p>}

                {loading ? (
                    <p>Yükleniyor...</p>
                ) : categories.length === 0 ? (
                    <p>Henüz kategori yok.</p>
                ) : (
                    <table style={styles.table}>
                        <thead>
                        <tr>
                            <th style={styles.th}>ID</th>
                            <th style={styles.th}>Kategori</th>
                            <th style={styles.th}>Üst Kategori</th>
                            <th style={styles.th}>İşlemler</th>
                        </tr>
                        </thead>
                        <tbody>
                        {categories.map((c) => {
                            // parentIds listesindeki ID'leri kategori adlarıyla eşleştiriyoruz.
                            const parentNames = categories
                                .filter((p) => (c.parentIds ?? []).some((id) => String(id) === String(p.id)))
                                .map((p) => p.name);

                            return (
                                <tr key={c.id}>
                                    <td style={styles.td}>{c.id}</td>

                                    <td style={styles.td}>
                                        {editingId === c.id ? (
                                            <input
                                                style={styles.editInput}
                                                value={editName}
                                                autoFocus
                                                onChange={(e) => setEditName(e.target.value)}
                                                onKeyDown={(e) => {
                                                    if (e.key === "Enter") handleUpdate(c.id);
                                                    if (e.key === "Escape") cancelEdit();
                                                }}
                                            />
                                        ) : (
                                            c.name
                                        )}
                                    </td>

                                    {/* Tabloda Üst Kategori Gösterimi / Düzenlemesi */}
                                    <td style={styles.td}>
                                        {editingId === c.id ? (
                                            <select
                                                style={styles.editInput}
                                                multiple
                                                size={4}
                                                aria-label={`${c.name} için üst kategoriler`}
                                                value={editParentIds}
                                                onChange={(e) =>
                                                    setEditParentIds(Array.from(e.target.selectedOptions, (option) => option.value))
                                                }
                                            >
                                                {categories
                                                    .filter((cat) => cat.id !== c.id)
                                                    .map((cat) => (
                                                        <option key={cat.id} value={String(cat.id)}>{cat.name}</option>
                                                    ))}
                                            </select>
                                        ) : (
                                            parentNames.length > 0
                                                ? parentNames.join(", ")
                                                : <span style={{ color: "#999" }}>-</span>
                                        )}
                                    </td>

                                    <td style={{ ...styles.td, textAlign: "right", whiteSpace: "nowrap" }}>
                                        {editingId === c.id ? (
                                            <>
                                                <button style={styles.save} onClick={() => handleUpdate(c.id)}>Kaydet</button>
                                                <button style={styles.gray} onClick={cancelEdit}>İptal</button>
                                            </>
                                        ) : (
                                            <>
                                                <button style={styles.gray} onClick={() => startEdit(c)}>Düzenle</button>
                                                <button style={styles.danger} onClick={() => handleDelete(c.id, c.name)}>Sil</button>
                                            </>
                                        )}
                                    </td>
                                </tr>
                            );
                        })}
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
    save: { ...btn, background: "#198754" },
    gray: { ...btn, background: "#6c757d" },
    danger: { ...btn, background: "#dc3545" },
    error: { color: "#dc3545" },
    table: { width: "100%", borderCollapse: "collapse" },
    th: { textAlign: "left", padding: 8, borderBottom: "2px solid #ddd" },
    td: { padding: 8, borderBottom: "1px solid #eee" },
};