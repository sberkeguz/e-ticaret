import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch } from "../api";
import StoreHeader from "../components/StoreHeader";
import { moveFavorites } from "../favorites";

const ADDRESS_API = "https://api.turkiyeapi.dev/v1";
const loadList = async (path) => {
    const response = await fetch(`${ADDRESS_API}/${path}`);
    if (!response.ok) throw new Error("Adres listesi yüklenemedi.");
    const body = await response.json();
    return (body.data ?? []).map(({ id, name }) => ({ id, name }))
        .sort((a, b) => a.name.localeCompare(b.name, "tr"));
};

const emptyProfile = {
    username: "",
    role: "",
    firstName: "",
    lastName: "",
    phone: "",
    city: "",
    district: "",
    neighborhood: "",
    address: "",
};

export default function Profile() {
    const [profile, setProfile] = useState(emptyProfile);
    const [provinces, setProvinces] = useState([]);
    const [districts, setDistricts] = useState([]);
    const [neighborhoods, setNeighborhoods] = useState([]);
    const [provinceId, setProvinceId] = useState("");
    const [districtId, setDistrictId] = useState("");
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [saved, setSaved] = useState(false);
    const [error, setError] = useState("");
    const [addressError, setAddressError] = useState("");
    const [usernameDialog, setUsernameDialog] = useState(false);
    const [usernameDraft, setUsernameDraft] = useState("");
    const [currentPassword, setCurrentPassword] = useState("");
    const [usernameError, setUsernameError] = useState("");
    const [usernameSaving, setUsernameSaving] = useState(false);

    useEffect(() => {
        let active = true;
        apiFetch("/api/profile")
            .then(async (response) => {
                if (!response.ok) throw new Error("Profil bilgileri yüklenemedi.");
                const data = await response.json();
                if (active) setProfile({ ...emptyProfile, ...data });
            })
            .catch((loadError) => { if (active) setError(loadError.message); })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, []);

    useEffect(() => {
        let active = true;
        loadList("provinces?fields=id,name&limit=100")
            .then((data) => { if (active) setProvinces(data); })
            .catch(() => { if (active) setAddressError("İl listesi yüklenemedi."); });
        return () => { active = false; };
    }, []);

    useEffect(() => {
        if (!profile.city || provinces.length === 0) return undefined;
        const province = provinces.find((item) => item.name === profile.city);
        if (!province) return undefined;
        let active = true;
        const loadSavedAddress = async () => {
            try {
                const items = await loadList(`districts?provinceId=${province.id}&fields=id,name&limit=100`);
                if (!active) return;
                setProvinceId(String(province.id));
                setDistricts(items);
                const district = items.find((item) => item.name === profile.district);
                if (!district) return;
                setDistrictId(String(district.id));
                setNeighborhoods(await loadList(`neighborhoods?districtId=${district.id}&fields=id,name&limit=1000`));
            } catch {
                if (active) setAddressError("Kaydedilmiş adres listesi yüklenemedi.");
            }
        };
        loadSavedAddress();
        return () => { active = false; };
    }, [profile.city, profile.district, provinces]);

    const update = (field, value) => {
        setProfile((current) => ({ ...current, [field]: value }));
        setSaved(false);
    };

    const pickProvince = async (id) => {
        const province = provinces.find((item) => String(item.id) === id);
        setProvinceId(id);
        setDistrictId("");
        setDistricts([]);
        setNeighborhoods([]);
        setProfile((current) => ({ ...current, city: province?.name ?? "", district: "", neighborhood: "" }));
        if (!id) return;
        try {
            setDistricts(await loadList(`districts?provinceId=${id}&fields=id,name&limit=100`));
        } catch {
            setAddressError("İlçe listesi yüklenemedi.");
        }
    };

    const pickDistrict = async (id) => {
        const district = districts.find((item) => String(item.id) === id);
        setDistrictId(id);
        setNeighborhoods([]);
        setProfile((current) => ({ ...current, district: district?.name ?? "", neighborhood: "" }));
        if (!id) return;
        try {
            setNeighborhoods(await loadList(`neighborhoods?districtId=${id}&fields=id,name&limit=1000`));
        } catch {
            setAddressError("Mahalle listesi yüklenemedi.");
        }
    };

    const saveProfile = async (event) => {
        event.preventDefault();
        setError("");
        setSaved(false);
        setSaving(true);
        try {
            const response = await apiFetch("/api/profile", {
                method: "PUT",
                body: JSON.stringify({
                    firstName: profile.firstName,
                    lastName: profile.lastName,
                    phone: profile.phone,
                    city: profile.city,
                    district: profile.district,
                    neighborhood: profile.neighborhood,
                    address: profile.address,
                }),
            });
            if (!response.ok) {
                const body = await response.json().catch(() => null);
                throw new Error(body?.message || "Profil kaydedilemedi.");
            }
            setProfile({ ...emptyProfile, ...await response.json() });
            setSaved(true);
        } catch (saveError) {
            setError(saveError.message || "Profil kaydedilemedi.");
        } finally {
            setSaving(false);
        }
    };

    const changeUsername = async (event) => {
        event.preventDefault();
        setUsernameError("");
        setUsernameSaving(true);
        try {
            const response = await apiFetch("/api/profile/username", {
                method: "PUT",
                body: JSON.stringify({ username: usernameDraft, currentPassword }),
            });
            if (!response.ok) {
                const body = await response.json().catch(() => null);
                throw new Error(body?.message || "Kullanıcı adı değiştirilemedi.");
            }
            const data = await response.json();
            const authorization = response.headers.get("Authorization");
            if (authorization?.startsWith("Bearer ")) localStorage.setItem("token", authorization.slice(7));
            const previousUsername = localStorage.getItem("username");
            localStorage.setItem("username", data.username);
            try {
                moveFavorites(previousUsername, data.username);
            } catch {
                setError("Kullanıcı adınız değişti ancak favorileriniz taşınamadı. Tarayıcı depolama ayarlarınızı kontrol edin.");
            }
            window.dispatchEvent(new Event("username-changed"));
            setProfile((current) => ({ ...current, username: data.username }));
            setSaved(true);
            setUsernameDialog(false);
        } catch (changeError) {
            setUsernameError(changeError.message || "Kullanıcı adı değiştirilemedi.");
        } finally {
            setUsernameSaving(false);
            setCurrentPassword("");
        }
    };

    const openUsernameDialog = () => {
        setUsernameDraft(profile.username);
        setCurrentPassword("");
        setUsernameError("");
        setUsernameDialog(true);
    };

    return (
        <div style={styles.page}>
            <StoreHeader />
            <div style={styles.heading}>
                <div><h1 style={{ marginBottom: 4 }}>Profilim</h1><p style={styles.muted}>Kişisel ve teslimat bilgilerinizi yönetin.</p></div>
                <Link to="/home">Mağazaya dön</Link>
            </div>
            {error && <p role="alert" style={styles.error}>{error}</p>}
            {saved && <p role="status" style={styles.success}>Bilgileriniz kaydedildi.</p>}
            {loading ? <p>Profil yükleniyor...</p> : (
                <form onSubmit={saveProfile} style={styles.form}>
                    <section style={styles.card}>
                        <h2>Kişisel bilgiler</h2>
                        <div style={styles.grid}>
                            <label style={styles.field}>Ad<input style={styles.input} maxLength={60} value={profile.firstName} onChange={(event) => update("firstName", event.target.value)} /></label>
                            <label style={styles.field}>Soyad<input style={styles.input} maxLength={60} value={profile.lastName} onChange={(event) => update("lastName", event.target.value)} /></label>
                            <label style={styles.field}>Telefon<input style={styles.input} type="tel" maxLength={20} value={profile.phone} onChange={(event) => update("phone", event.target.value)} placeholder="05xx xxx xx xx" /></label>
                            <div style={styles.field}>Kullanıcı adı<div style={styles.usernameRow}><input style={styles.input} value={profile.username} readOnly /><button type="button" onClick={openUsernameDialog} style={styles.secondaryButton}>Değiştir</button></div></div>
                        </div>
                    </section>
                    <section style={styles.card}>
                        <h2>Varsayılan teslimat adresi</h2>
                        <div style={styles.grid}>
                            <label style={styles.field}>İl
                                <select style={styles.input} value={provinceId} onChange={(event) => pickProvince(event.target.value)}>
                                    <option value="">{provinces.length ? "İl seçin" : "İller yükleniyor..."}</option>
                                    {provinces.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                                </select>
                            </label>
                            <label style={styles.field}>İlçe
                                <select style={styles.input} value={districtId} disabled={!provinceId} onChange={(event) => pickDistrict(event.target.value)}>
                                    <option value="">{provinceId ? "İlçe seçin" : "Önce il seçin"}</option>
                                    {districts.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                                </select>
                            </label>
                            <label style={styles.field}>Mahalle
                                <select style={styles.input} value={profile.neighborhood} disabled={!districtId} onChange={(event) => update("neighborhood", event.target.value)}>
                                    <option value="">{districtId ? "Mahalle seçin" : "Önce ilçe seçin"}</option>
                                    {neighborhoods.map((item) => <option key={item.id} value={item.name}>{item.name}</option>)}
                                </select>
                            </label>
                            <label style={{ ...styles.field, gridColumn: "1 / -1" }}>Açık adres
                                <textarea style={styles.input} rows={3} maxLength={300} value={profile.address} onChange={(event) => update("address", event.target.value)} />
                            </label>
                        </div>
                        {addressError && <p role="alert" style={styles.error}>{addressError}</p>}
                    </section>
                    <button type="submit" disabled={saving} style={styles.primaryButton}>{saving ? "Kaydediliyor..." : "Değişiklikleri kaydet"}</button>
                </form>
            )}
            {usernameDialog && (
                <div style={styles.backdrop} onMouseDown={(event) => {
                    if (event.target === event.currentTarget && !usernameSaving) setUsernameDialog(false);
                }}>
                    <form role="dialog" aria-modal="true" aria-labelledby="username-title" onSubmit={changeUsername} style={styles.dialog}>
                        <h2 id="username-title">Kullanıcı adını değiştir</h2>
                        <p style={styles.muted}>Güvenliğiniz için mevcut şifrenizi girin.</p>
                        {usernameError && <p role="alert" style={styles.error}>{usernameError}</p>}
                        <label style={styles.field}>Yeni kullanıcı adı<input style={styles.input} autoComplete="username" autoFocus required maxLength={60} value={usernameDraft} onChange={(event) => setUsernameDraft(event.target.value)} /></label>
                        <label style={styles.field}>Mevcut şifre<input style={styles.input} type="password" autoComplete="current-password" required value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} /></label>
                        <div style={styles.dialogActions}>
                            <button type="button" disabled={usernameSaving} onClick={() => setUsernameDialog(false)} style={styles.secondaryButton}>Vazgeç</button>
                            <button type="submit" disabled={usernameSaving} style={styles.primaryButton}>{usernameSaving ? "Doğrulanıyor..." : "Onayla ve değiştir"}</button>
                        </div>
                    </form>
                </div>
            )}
        </div>
    );
}

const styles = {
    page: { maxWidth: 1050, margin: "0 auto", padding: 24, fontFamily: "system-ui, sans-serif", color: "#222" },
    heading: { display: "flex", justifyContent: "space-between", alignItems: "center", gap: 16, margin: "24px 0" },
    muted: { color: "#666", marginTop: 4 },
    form: { display: "grid", gap: 16 },
    card: { padding: 20, border: "1px solid #ddd", borderRadius: 10, background: "#fff" },
    grid: { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(230px, 1fr))", gap: 16 },
    field: { display: "grid", gap: 6, fontSize: 14, fontWeight: 600 },
    input: { width: "100%", minWidth: 0, boxSizing: "border-box", padding: "10px 12px", border: "1px solid #bbb", borderRadius: 6, font: "inherit", fontWeight: 400 },
    usernameRow: { display: "flex", gap: 8 },
    primaryButton: { padding: "10px 16px", border: "1px solid #174ea6", borderRadius: 6, background: "#174ea6", color: "#fff", cursor: "pointer" },
    secondaryButton: { padding: "10px 14px", border: "1px solid #bbb", borderRadius: 6, background: "#fff", cursor: "pointer", whiteSpace: "nowrap" },
    success: { padding: 12, color: "#176b37", background: "#e9f7ee", borderRadius: 6 },
    error: { padding: 12, color: "#9b1c1c", background: "#fdecec", borderRadius: 6 },
    backdrop: { position: "fixed", zIndex: 1000, inset: 0, display: "grid", placeItems: "center", padding: 16, background: "#0008" },
    dialog: { display: "grid", gap: 14, width: "min(440px, 100%)", padding: 24, boxSizing: "border-box", background: "#fff", borderRadius: 12, boxShadow: "0 15px 50px #0004" },
    dialogActions: { display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 8 },
};
