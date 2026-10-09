import { Fragment, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch } from "../api";

const ROLES = ["USER", "ADMIN"];

export default function AdminUsers() {
    const [users, setUsers] = useState([]);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);

    // Düzenleme durumu
    const [editingId, setEditingId] = useState(null);
    const [form, setForm] = useState({ username: "", role: "USER", password: "" });

    // Yeni kullanıcı formu
    const [newUser, setNewUser] = useState({ username: "", password: "", role: "USER" });

    // Giriş geçmişi: hangi kullanıcının geçmişi açık
    const [historyId, setHistoryId] = useState(null);
    const [history, setHistory] = useState([]);
    const [historyLoading, setHistoryLoading] = useState(false);

    const currentUsername = localStorage.getItem("username");

    const formatDate = (iso) => (iso ? new Date(iso).toLocaleString("tr-TR") : "Hiç girmedi");

    // Sunucunun gönderdiği "message" alanını okur
    const readError = async (res, fallback) => {
        const err = await res.json().catch(() => null);
        return err?.message || fallback;
    };

    useEffect(() => {
        const load = async () => {
            try {
                const res = await apiFetch("/api/admin/users");
                if (!res.ok) throw new Error();
                setUsers(await res.json());
            } catch {
                setError("Kullanıcılar yüklenemedi.");
            } finally {
                setLoading(false);
            }
        };
        load();
    }, []);

    const startEdit = (u) => {
        setError("");
        setEditingId(u.id);
        setForm({ username: u.username, role: u.role, password: "" });
    };

    const cancelEdit = () => setEditingId(null);

    // Güncellenen kullanıcıyı listede yerine koy
    const replaceUser = (updated) =>
        setUsers(users.map((u) => (u.id === updated.id ? updated : u)));

    // Yeni kullanıcı ekle: POST
    const handleCreate = async (e) => {
        e.preventDefault();
        setError("");
        try {
            const res = await apiFetch("/api/admin/users", {
                method: "POST",
                body: JSON.stringify(newUser),
            });
            if (!res.ok) {
                setError(await readError(res, "Kullanıcı eklenemedi."));
                return;
            }
            const created = await res.json();
            setUsers([...users, created]);
            setNewUser({ username: "", password: "", role: "USER" });
        } catch {
            setError("Sunucuya ulaşılamadı.");
        }
    };

    // Bilgileri kaydet: PUT
    const handleSave = async (id) => {
        setError("");
        if (!form.username.trim()) {
            setError("Kullanıcı adı boş olamaz.");
            return;
        }
        try {
            const res = await apiFetch(`/api/admin/users/${id}`, {
                method: "PUT",
                body: JSON.stringify({
                    username: form.username.trim(),
                    role: form.role,
                    password: form.password || null, // boşsa şifre değişmez
                }),
            });
            if (!res.ok) {
                setError(await readError(res, "Kullanıcı güncellenemedi."));
                return;
            }
            replaceUser(await res.json());
            cancelEdit();
        } catch {
            setError("Sunucuya ulaşılamadı.");
        }
    };

    // Aktif / pasif: PATCH
    const handleToggle = async (u) => {
        setError("");
        try {
            const res = await apiFetch(`/api/admin/users/${u.id}/active`, {
                method: "PATCH",
                body: JSON.stringify({ active: !u.active }),
            });
            if (!res.ok) {
                setError(await readError(res, "Durum değiştirilemedi."));
                return;
            }
            replaceUser(await res.json());
        } catch {
            setError("Sunucuya ulaşılamadı.");
        }
    };

    // Kullanıcı sil: DELETE
    const handleDelete = async (u) => {
        if (!window.confirm(`"${u.username}" kullanıcısı silinsin mi?`)) return;
        setError("");
        try {
            const res = await apiFetch(`/api/admin/users/${u.id}`, { method: "DELETE" });
            if (!res.ok) {
                setError(await readError(res, "Kullanıcı silinemedi."));
                return;
            }
            setUsers(users.filter((x) => x.id !== u.id));
            if (editingId === u.id) cancelEdit();
            if (historyId === u.id) setHistoryId(null);
        } catch {
            setError("Sunucuya ulaşılamadı.");
        }
    };

    // Giriş geçmişi: aynı butona tekrar basınca kapanır
    const toggleHistory = async (u) => {
        if (historyId === u.id) {
            setHistoryId(null);
            return;
        }
        setError("");
        setHistoryId(u.id);
        setHistory([]);
        setHistoryLoading(true);
        try {
            const res = await apiFetch(`/api/admin/users/${u.id}/logins`);
            if (!res.ok) {
                setError(await readError(res, "Giriş geçmişi yüklenemedi."));
                setHistoryId(null);
                return;
            }
            setHistory(await res.json());
        } catch {
            setError("Sunucuya ulaşılamadı.");
            setHistoryId(null);
        } finally {
            setHistoryLoading(false);
        }
    };

    return (
        <div style={styles.page}>
            <Link to="/admin">← Admin Paneli</Link>

            <section style={styles.card}>
                <h2 style={{ marginTop: 0 }}>Kullanıcılar</h2>

                {/* Yeni kullanıcı ekleme formu */}
                <form onSubmit={handleCreate} style={styles.createForm}>
                    <input
                        style={styles.input}
                        placeholder="Kullanıcı adı"
                        value={newUser.username}
                        onChange={(e) => setNewUser({ ...newUser, username: e.target.value })}
                    />
                    <input
                        style={styles.input}
                        type="password"
                        placeholder="Şifre (en az 5 karakter)"
                        value={newUser.password}
                        onChange={(e) => setNewUser({ ...newUser, password: e.target.value })}
                    />
                    <select
                        style={styles.input}
                        value={newUser.role}
                        onChange={(e) => setNewUser({ ...newUser, role: e.target.value })}
                    >
                        {ROLES.map((r) => (
                            <option key={r} value={r}>{r}</option>
                        ))}
                    </select>
                    <button style={styles.save} type="submit">Ekle</button>
                </form>

                {error && <p style={styles.error}>{error}</p>}

                {loading ? (
                    <p>Yükleniyor...</p>
                ) : users.length === 0 ? (
                    <p>Kullanıcı yok.</p>
                ) : (
                    <table style={styles.table}>
                        <thead>
                        <tr>
                            <th style={styles.th}>ID</th>
                            <th style={styles.th}>Kullanıcı adı</th>
                            <th style={styles.th}>Rol</th>
                            <th style={styles.th}>Yeni şifre</th>
                            <th style={styles.th}>Durum</th>
                            <th style={styles.th}>Son giriş</th>
                            <th style={styles.th}></th>
                        </tr>
                        </thead>
                        <tbody>
                        {users.map((u) => {
                            const editing = editingId === u.id;
                            const isSelf = u.username === currentUsername;

                            return (
                                <Fragment key={u.id}>
                                    <tr>
                                        <td style={styles.td}>{u.id}</td>

                                        <td style={styles.td}>
                                            {editing ? (
                                                <input
                                                    style={styles.input}
                                                    value={form.username}
                                                    // Kendi kullanıcı adını değiştirirsen token geçersiz olur, o yüzden kapalı
                                                    disabled={isSelf}
                                                    onChange={(e) => setForm({ ...form, username: e.target.value })}
                                                />
                                            ) : (
                                                u.username
                                            )}
                                        </td>

                                        <td style={styles.td}>
                                            {editing ? (
                                                <select
                                                    style={styles.input}
                                                    value={form.role}
                                                    disabled={isSelf}
                                                    onChange={(e) => setForm({ ...form, role: e.target.value })}
                                                >
                                                    {ROLES.map((r) => (
                                                        <option key={r} value={r}>{r}</option>
                                                    ))}
                                                </select>
                                            ) : (
                                                u.role
                                            )}
                                        </td>

                                        <td style={styles.td}>
                                            {editing ? (
                                                <input
                                                    style={styles.input}
                                                    type="password"
                                                    placeholder="Boş = değişmez"
                                                    value={form.password}
                                                    onChange={(e) => setForm({ ...form, password: e.target.value })}
                                                />
                                            ) : (
                                                "••••••"
                                            )}
                                        </td>

                                        <td style={styles.td}>
                                            <span style={u.active ? styles.on : styles.off}>
                                                {u.active ? "Aktif" : "Pasif"}
                                            </span>
                                        </td>

                                        <td style={styles.td}>{formatDate(u.lastLoginAt)}</td>

                                        <td style={{ ...styles.td, textAlign: "right", whiteSpace: "nowrap" }}>
                                            {editing ? (
                                                <>
                                                    <button style={styles.save} onClick={() => handleSave(u.id)}>Kaydet</button>
                                                    <button style={styles.gray} onClick={cancelEdit}>İptal</button>
                                                </>
                                            ) : (
                                                <>
                                                    <button style={styles.gray} onClick={() => startEdit(u)}>Düzenle</button>
                                                    <button style={styles.gray} onClick={() => toggleHistory(u)}>
                                                        {historyId === u.id ? "Gizle" : "Girişler"}
                                                    </button>
                                                    <button
                                                        style={u.active ? styles.danger : styles.save}
                                                        disabled={isSelf}
                                                        title={isSelf ? "Kendi hesabını pasif yapamazsın" : ""}
                                                        onClick={() => handleToggle(u)}
                                                    >
                                                        {u.active ? "Pasif yap" : "Aktif yap"}
                                                    </button>
                                                    <button
                                                        style={styles.danger}
                                                        disabled={isSelf}
                                                        title={isSelf ? "Kendi hesabını silemezsin" : ""}
                                                        onClick={() => handleDelete(u)}
                                                    >
                                                        Sil
                                                    </button>
                                                </>
                                            )}
                                        </td>
                                    </tr>

                                    {/* Giriş geçmişi satırı */}
                                    {historyId === u.id && (
                                        <tr>
                                            <td colSpan={7} style={styles.historyCell}>
                                                {historyLoading ? (
                                                    "Yükleniyor..."
                                                ) : history.length === 0 ? (
                                                    "Giriş kaydı yok."
                                                ) : (
                                                    <table style={styles.table}>
                                                        <thead>
                                                        <tr>
                                                            <th style={styles.th}>Tarih</th>
                                                            <th style={styles.th}>IP</th>
                                                            <th style={styles.th}>Tarayıcı</th>
                                                        </tr>
                                                        </thead>
                                                        <tbody>
                                                        {history.map((h) => (
                                                            <tr key={h.id}>
                                                                <td style={styles.td}>{formatDate(h.loggedInAt)}</td>
                                                                <td style={styles.td}>{h.ipAddress}</td>
                                                                <td style={styles.td}>{h.userAgent}</td>
                                                            </tr>
                                                        ))}
                                                        </tbody>
                                                    </table>
                                                )}
                                            </td>
                                        </tr>
                                    )}
                                </Fragment>
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
const badge = { padding: "2px 10px", borderRadius: 12, fontSize: 13, color: "#fff" };

const styles = {
    page: { maxWidth: 1100, margin: "0 auto", padding: 24, fontFamily: "system-ui, sans-serif" },
    card: { marginTop: 16, padding: 20, border: "1px solid #ddd", borderRadius: 8 },
    error: { color: "#dc3545" },
    createForm: { display: "flex", gap: 8, marginBottom: 16 },
    table: { width: "100%", borderCollapse: "collapse" },
    th: { textAlign: "left", padding: 8, borderBottom: "2px solid #ddd" },
    td: { padding: 8, borderBottom: "1px solid #eee" },
    historyCell: { padding: 12, background: "#f8f9fa", borderBottom: "1px solid #eee", fontSize: 13 },
    input: { width: "100%", padding: 6, fontSize: 14, border: "1px solid #0d6efd", borderRadius: 4, boxSizing: "border-box" },
    on: { ...badge, background: "#198754" },
    off: { ...badge, background: "#6c757d" },
    save: { ...btn, background: "#198754" },
    gray: { ...btn, background: "#6c757d" },
    danger: { ...btn, background: "#dc3545" },
};