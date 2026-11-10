import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiFetch } from "../api";

const ROLES = ["USER", "ADMIN"];
const formatDate = (value) => value
    ? new Date(value).toLocaleString("tr-TR", { dateStyle: "medium", timeStyle: "short" })
    : "Henüz giriş yok";
const readError = async (response, fallback) => {
    const body = await response.json().catch(() => null);
    return body?.message || fallback;
};

export default function AdminUsers() {
    const navigate = useNavigate();
    const [users, setUsers] = useState([]);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState("");
    const [creating, setCreating] = useState(false);
    const [saving, setSaving] = useState(false);
    const [newUser, setNewUser] = useState({ username: "", password: "", role: "USER" });
    const [modalMode, setModalMode] = useState(null);
    const [selectedUser, setSelectedUser] = useState(null);
    const [form, setForm] = useState({ username: "", role: "USER", password: "" });
    const [history, setHistory] = useState([]);
    const [historyLoading, setHistoryLoading] = useState(false);
    const currentUsername = localStorage.getItem("username");

    useEffect(() => {
        let active = true;
        apiFetch("/api/admin/users")
            .then(async (response) => {
                if (!response.ok) throw new Error(await readError(response, "Kullanıcılar yüklenemedi."));
                const data = await response.json();
                if (active) setUsers(data);
            })
            .catch((loadError) => { if (active) setError(loadError.message || "Kullanıcılar yüklenemedi."); })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, []);

    useEffect(() => {
        if (!modalMode) return undefined;
        const closeOnEscape = (event) => { if (event.key === "Escape") setModalMode(null); };
        window.addEventListener("keydown", closeOnEscape);
        return () => window.removeEventListener("keydown", closeOnEscape);
    }, [modalMode]);

    const filteredUsers = useMemo(() => users.filter((user) =>
        user.username.toLocaleLowerCase("tr").includes(search.toLocaleLowerCase("tr"))
    ), [users, search]);

    const replaceUser = (updated) => setUsers((current) => current.map((user) => user.id === updated.id ? updated : user));

    const createUser = async (event) => {
        event.preventDefault();
        setError("");
        setCreating(true);
        try {
            const response = await apiFetch("/api/admin/users", {
                method: "POST",
                body: JSON.stringify(newUser),
            });
            if (!response.ok) throw new Error(await readError(response, "Kullanıcı eklenemedi."));
            const created = await response.json();
            setUsers((current) => [...current, created]);
            setNewUser({ username: "", password: "", role: "USER" });
        } catch (requestError) {
            setError(requestError.message || "Sunucuya ulaşılamadı.");
        } finally {
            setCreating(false);
        }
    };

    const openEdit = (user) => {
        setError("");
        setSelectedUser(user);
        setForm({ username: user.username, role: user.role, password: "" });
        setModalMode("edit");
    };

    const saveUser = async (event) => {
        event.preventDefault();
        if (!form.username.trim()) {
            setError("Kullanıcı adı boş olamaz.");
            return;
        }
        setSaving(true);
        setError("");
        try {
            const response = await apiFetch(`/api/admin/users/${selectedUser.id}`, {
                method: "PUT",
                body: JSON.stringify({
                    username: form.username.trim(),
                    role: form.role,
                    password: form.password || null,
                }),
            });
            if (!response.ok) throw new Error(await readError(response, "Kullanıcı güncellenemedi."));
            replaceUser(await response.json());
            setModalMode(null);
        } catch (requestError) {
            setError(requestError.message || "Kullanıcı güncellenemedi.");
        } finally {
            setSaving(false);
        }
    };

    const openHistory = async (user) => {
        setSelectedUser(user);
        setHistory([]);
        setHistoryLoading(true);
        setModalMode("history");
        setError("");
        try {
            const response = await apiFetch(`/api/admin/users/${user.id}/logins`);
            if (!response.ok) throw new Error(await readError(response, "Giriş geçmişi yüklenemedi."));
            setHistory(await response.json());
        } catch (requestError) {
            setError(requestError.message || "Giriş geçmişi yüklenemedi.");
        } finally {
            setHistoryLoading(false);
        }
    };

    const toggleActive = async (user) => {
        setError("");
        try {
            const response = await apiFetch(`/api/admin/users/${user.id}/active`, {
                method: "PATCH",
                body: JSON.stringify({ active: !user.active }),
            });
            if (!response.ok) throw new Error(await readError(response, "Kullanıcı durumu değiştirilemedi."));
            replaceUser(await response.json());
        } catch (requestError) {
            setError(requestError.message || "Kullanıcı durumu değiştirilemedi.");
        }
    };

    const deleteUser = async (user) => {
        if (!window.confirm(`"${user.username}" kullanıcısı silinsin mi?`)) return;
        setError("");
        try {
            const response = await apiFetch(`/api/admin/users/${user.id}`, { method: "DELETE" });
            if (!response.ok) throw new Error(await readError(response, "Kullanıcı silinemedi."));
            setUsers((current) => current.filter((item) => item.id !== user.id));
            if (selectedUser?.id === user.id) setModalMode(null);
        } catch (requestError) {
            setError(requestError.message || "Kullanıcı silinemedi.");
        }
    };

    const activeCount = users.filter((user) => user.active).length;

    return (
        <>
            <div className="page-heading">
                <div>
                    <div className="eyebrow">MAĞAZA TOPLULUĞU</div>
                    <h1 className="page-title">Kullanıcılar</h1>
                    <p className="page-subtitle">Hesapları, erişim rollerini ve giriş hareketlerini yönetin.</p>
                </div>
                <span className="badge badge-gray">{users.length} hesap</span>
            </div>

            <section className="stat-grid" style={{ gridTemplateColumns: "repeat(3, minmax(0, 1fr))" }}>
                <article className="surface stat-card"><span className="stat-icon">♙</span><span><span className="stat-value">{users.length}</span><span className="stat-label">Toplam kullanıcı</span></span></article>
                <article className="surface stat-card"><span className="stat-icon" style={{ color: "var(--green)", background: "var(--mint)" }}>✓</span><span><span className="stat-value">{activeCount}</span><span className="stat-label">Aktif hesap</span></span></article>
                <article className="surface stat-card"><span className="stat-icon" style={{ color: "var(--amber)", background: "#fff4e3" }}>◷</span><span><span className="stat-value">{users.length - activeCount}</span><span className="stat-label">Pasif hesap</span></span></article>
            </section>

            {error && <p className="error-banner" role="alert">{error}</p>}

            <section className="surface surface-pad" style={{ marginBottom: 18 }}>
                <div className="toolbar">
                    <div><h2 className="section-title">Yeni kullanıcı</h2><p className="section-caption">Yönetici panelinden doğrudan hesap oluşturun.</p></div>
                </div>
                <form onSubmit={createUser} className="toolbar" style={{ alignItems: "flex-end", marginBottom: 0 }}>
                    <label className="field" style={{ flex: 1 }}>
                        <span className="field-label">Kullanıcı adı</span>
                        <input className="input" required value={newUser.username} onChange={(event) => setNewUser((current) => ({ ...current, username: event.target.value }))} placeholder="Yeni kullanıcı adı" />
                    </label>
                    <label className="field" style={{ flex: 1 }}>
                        <span className="field-label">Başlangıç şifresi</span>
                        <input className="input" type="password" minLength={8} required value={newUser.password} onChange={(event) => setNewUser((current) => ({ ...current, password: event.target.value }))} placeholder="En az 8 karakter, harf ve rakam" />
                    </label>
                    <label className="field" style={{ width: 145 }}>
                        <span className="field-label">Rol</span>
                        <select className="select" value={newUser.role} onChange={(event) => setNewUser((current) => ({ ...current, role: event.target.value }))}>
                            {ROLES.map((role) => <option key={role} value={role}>{role === "ADMIN" ? "Yönetici" : "Kullanıcı"}</option>)}
                        </select>
                    </label>
                    <button className="button button-primary" disabled={creating} type="submit">{creating ? "Ekleniyor…" : "+ Kullanıcı ekle"}</button>
                </form>
            </section>

            <section className="surface surface-pad">
                <div className="toolbar">
                    <div><h2 className="section-title">Hesap listesi</h2><p className="section-caption">{filteredUsers.length} kullanıcı görüntüleniyor</p></div>
                    <div className="search-field"><span className="search-glyph">⌕</span><input className="input" placeholder="Kullanıcı ara..." value={search} onChange={(event) => setSearch(event.target.value)} /></div>
                </div>
                {loading ? <div className="loading-state">Kullanıcılar yükleniyor…</div> : filteredUsers.length === 0 ? (
                    <div className="empty-state"><div className="empty-icon">♙</div><h3>{search ? "Eşleşen hesap bulunamadı" : "Henüz kullanıcı yok"}</h3><p>Başka bir arama kelimesi deneyin veya yeni hesap oluşturun.</p></div>
                ) : (
                    <div className="table-wrap">
                        <table className="data-table">
                            <thead><tr><th>Kullanıcı</th><th>Rol</th><th>Durum</th><th>Son giriş</th><th>Siparişler</th><th style={{ textAlign: "right" }}>İşlemler</th></tr></thead>
                            <tbody>
                                {filteredUsers.map((user) => {
                                    const isSelf = user.username === currentUsername;
                                    return (
                                        <tr key={user.id}>
                                            <td><div className="user-chip"><span className="avatar small">{user.username.slice(0, 2)}</span><span><span className="cell-primary">{user.username}{isSelf ? " (siz)" : ""}</span><span className="cell-sub">Hesap #{user.id}</span></span></div></td>
                                            <td><span className={`badge ${user.role === "ADMIN" ? "badge-amber" : "badge-gray"}`}>{user.role === "ADMIN" ? "Yönetici" : "Kullanıcı"}</span></td>
                                            <td><span className={`badge ${user.active ? "badge-green" : "badge-gray"}`}>{user.active ? "Aktif" : "Pasif"}</span></td>
                                            <td>
                                                <button className="button button-quiet button-small" onClick={() => openHistory(user)}>
                                                    {user.lastLoginAt ? formatDate(user.lastLoginAt) : "Giriş geçmişi"}
                                                </button>
                                            </td>
                                            <td>
                                                <button className="button button-quiet button-small" onClick={() => navigate(`/admin/orders?user=${encodeURIComponent(user.username)}`)}>Siparişler</button>
                                            </td>
                                            <td>
                                                <div className="button-row" style={{ justifyContent: "flex-end" }}>
                                                    <button className="button button-quiet button-small" onClick={() => openEdit(user)}>Düzenle</button>
                                                    <button className={`button button-small ${user.active ? "button-quiet" : "button-primary"}`} disabled={isSelf} title={isSelf ? "Kendi hesabınızın durumunu değiştiremezsiniz." : ""} onClick={() => toggleActive(user)}>{user.active ? "Pasifleştir" : "Etkinleştir"}</button>
                                                    <button className="button button-danger button-small" disabled={isSelf} title={isSelf ? "Kendi hesabınızı silemezsiniz." : ""} onClick={() => deleteUser(user)}>Sil</button>
                                                </div>
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>

            {modalMode && selectedUser && (
                <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setModalMode(null); }}>
                    {modalMode === "edit" ? (
                        <form className="modal-card" onSubmit={saveUser}>
                            <div className="modal-head">
                                <div><h2>Kullanıcıyı düzenle</h2><p>{selectedUser.username} hesabının bilgilerini güncelleyin.</p></div>
                                <button className="button button-quiet button-icon" type="button" aria-label="Kapat" onClick={() => setModalMode(null)}>×</button>
                            </div>
                            <div className="modal-body" style={{ display: "grid", gap: 15 }}>
                                {error && <p className="error-banner" role="alert">{error}</p>}
                                <label className="field"><span className="field-label">Kullanıcı adı</span><input className="input" autoFocus disabled={selectedUser.username === currentUsername} value={form.username} onChange={(event) => setForm((current) => ({ ...current, username: event.target.value }))} /></label>
                                <label className="field"><span className="field-label">Rol</span><select className="select" disabled={selectedUser.username === currentUsername} value={form.role} onChange={(event) => setForm((current) => ({ ...current, role: event.target.value }))}>{ROLES.map((role) => <option key={role} value={role}>{role === "ADMIN" ? "Yönetici" : "Kullanıcı"}</option>)}</select></label>
                                <label className="field"><span className="field-label">Yeni şifre</span><input className="input" type="password" minLength={8} value={form.password} onChange={(event) => setForm((current) => ({ ...current, password: event.target.value }))} placeholder="Boş bırakılırsa değişmez" /></label>
                            </div>
                            <div className="modal-actions"><button className="button button-quiet" type="button" onClick={() => setModalMode(null)}>Vazgeç</button><button className="button button-primary" type="submit" disabled={saving}>{saving ? "Kaydediliyor…" : "Değişiklikleri kaydet"}</button></div>
                        </form>
                    ) : (
                        <section className="modal-card modal-card-wide" aria-labelledby="history-title">
                            <div className="modal-head">
                                <div><h2 id="history-title">Giriş geçmişi</h2><p>{selectedUser.username} · Son giriş: {formatDate(selectedUser.lastLoginAt)}</p></div>
                                <button className="button button-quiet button-icon" type="button" aria-label="Kapat" onClick={() => setModalMode(null)}>×</button>
                            </div>
                            <div className="modal-body">
                                {error && <p className="error-banner" role="alert">{error}</p>}
                                {historyLoading ? <div className="loading-state">Giriş kayıtları yükleniyor…</div> : history.length ? (
                                    <div className="history-list">
                                        {history.map((entry) => (
                                            <div className="history-row" key={entry.id}>
                                                <span className="history-dot" />
                                                <span><span className="history-time">{formatDate(entry.loggedInAt)}</span><span className="history-caption">Başarılı oturum açma</span></span>
                                            </div>
                                        ))}
                                    </div>
                                ) : <div className="empty-state"><div className="empty-icon">◷</div><h3>Giriş kaydı bulunamadı</h3><p>Bu kullanıcı için görüntülenecek bir giriş kaydı yok.</p></div>}
                            </div>
                            <div className="modal-actions"><button className="button button-quiet" type="button" onClick={() => setModalMode(null)}>Kapat</button></div>
                        </section>
                    )}
                </div>
            )}
        </>
    );
}
