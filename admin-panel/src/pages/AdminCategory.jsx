import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch } from "../api";
const [parentId, setParentId] = useState("");


export default function AdminCategory() {
    const [categories, setCategories] = useState([]);
    const [name, setName] = useState("");
    // Alt kategori mantığı için eklendi
    const [parentId, setParentId] = useState(""); 
    
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);

    // Düzenleme durumu
    const [editingId, setEditingId] = useState(null);
    const [editName, setEditName] = useState("");
    const [editParentId, setEditParentId] = useState(""); // Düzenlerken üst kategoriyi değiştirmek için

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
                // parentId boş string ise null gönderiyoruz
                body: JSON.stringify({ 
                    name: name.trim(),
                    parentId: parentId ? Number(parentId) : null
                }),
            });
            if (!res.ok) {
                setError(await readError(res, "Kategori eklenemedi. Bu isim zaten kayıtlı olabilir."));
                return;
            }
            const saved = await res.json();
            setCategories([...categories, saved]);
            setName("");
            setParentId(""); // Başarıyla eklenince select kutusunu da sıfırla
        } catch {
            setError("Sunucuya ulaşılamadı.");
        }
    };

    const startEdit = (c) => {
        setError("");
        setEditingId(c.id);
        setEditName(c.name);
        setEditParentId(c.parentId || ""); // Düzenleme modunda mevcut parentId'yi al
    };

    const cancelEdit = () => {
        setEditingId(null);
        setEditName("");
        setEditParentId("");
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
                body: JSON.stringify({ 
                    name: editName.trim(),
                    parentId: editParentId ? Number(editParentId) : null 
                }),
            });
            if (!res.ok) {
                setError(await readError(res, "Kategori güncellenemedi."));
                return;
            }
            const updated = await res.json();
            setCategories(categories.map((c) => (c.id === id ? updated : c)));
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
                    
                    {/* YENİ EKLENEN SELECT KUTUSU BURADA */}
                    <select 
                        style={styles.input} 
                        value={parentId} 
                        onChange={(e) => setParentId(e.target.value)}
                    >
                        <option value="">Üst kategori yok</option>
                        {categories.map((c) => (
                            <option key={c.id} value={c.id}>{c.name}</option>
                        ))}
                    </select>

                    <button style={styles.add} type="submit">Ekle</button>
                </form>

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
                            // Üst kategori adını tabloda göstermek için buluyoruz
                            const parentCat = categories.find(p => p.id === c.parentId);

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
                                                value={editParentId} 
                                                onChange={(e) => setEditParentId(e.target.value)}
                                            >
                                                <option value="">Üst kategori yok</option>
                                                {categories
                                                    .filter(cat => cat.id !== c.id) // Kendisini üst kategori seçemesin diye
                                                    .map((cat) => (
                                                    <option key={cat.id} value={cat.id}>{cat.name}</option>
                                                ))}
                                            </select>
                                        ) : (
                                            parentCat ? parentCat.name : <span style={{ color: '#999' }}>-</span>
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