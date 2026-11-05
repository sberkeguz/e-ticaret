import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { apiFetch } from "../api";
import { clearRemoved, getCart } from "../cart";

const money = (value) => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });
const ADDRESS_API = "https://api.turkiyeapi.dev/v1";
const loadList = (path) => fetch(`${ADDRESS_API}/${path}`)
    .then((response) => response.json())
    .then((body) => (body.data ?? []).map(({ id, name }) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name, "tr")));
const digits = (value) => value.replace(/\D/g, "");

const blankForm = {
    firstName: "", lastName: "", phone: "", city: "", district: "", neighborhood: "", address: "",
    cardHolder: "", cardNumber: "", expiry: "", cvv: "",
};

export default function Checkout() {
    const navigate = useNavigate();
    const [items] = useState(getCart);
    const [form, setForm] = useState(blankForm);
    const [error, setError] = useState("");
    const [done, setDone] = useState(false);
    const [saving, setSaving] = useState(false);
    const [provinces, setProvinces] = useState([]);
    const [districts, setDistricts] = useState([]);
    const [neighborhoods, setNeighborhoods] = useState([]);
    const [provinceId, setProvinceId] = useState("");
    const [districtId, setDistrictId] = useState("");
    const [addressLoadFailed, setAddressLoadFailed] = useState(false);

    useEffect(() => {
        loadList("provinces?fields=id,name&limit=100").then(setProvinces).catch(() => setAddressLoadFailed(true));
    }, []);

    const pickProvince = (id) => {
        const province = provinces.find((item) => String(item.id) === id);
        setProvinceId(id);
        setDistrictId("");
        setDistricts([]);
        setNeighborhoods([]);
        setForm((current) => ({ ...current, city: province?.name ?? "", district: "", neighborhood: "" }));
        if (id) loadList(`districts?provinceId=${id}&fields=id,name&limit=100`).then(setDistricts).catch(() => setAddressLoadFailed(true));
    };

    const pickDistrict = (id) => {
        const district = districts.find((item) => String(item.id) === id);
        setDistrictId(id);
        setNeighborhoods([]);
        setForm((current) => ({ ...current, district: district?.name ?? "", neighborhood: "" }));
        if (id) loadList(`neighborhoods?districtId=${id}&fields=id,name&limit=1000`).then(setNeighborhoods).catch(() => setAddressLoadFailed(true));
    };

    const total = items.reduce((sum, item) => sum + Number(item.price) * item.quantity, 0);
    const lettersOnly = (value) => value.replace(/[^\p{L}\s'.-]/gu, "");
    const phoneChars = (value) => {
        const raw = digits(value).slice(0, 11);
        return [raw.slice(0, 4), raw.slice(4, 7), raw.slice(7, 9), raw.slice(9, 11)].filter(Boolean).join(" ");
    };
    const update = (field, value) => setForm((current) => ({ ...current, [field]: value }));

    // Gerçek değer state'te tutulur; ekranda maskelenmiş hali gösterilir. Yazma sadece sona yapılır.
    const reconcile = (previous, typed, max) => {
        const chars = typed.replace(/[\s/]/g, "");
        if (!chars.includes("*")) return digits(chars).slice(0, max);
        if (chars.length <= previous.length) return previous.slice(0, chars.length);
        return (previous + digits(chars.slice(previous.length))).slice(0, max);
    };
    const maskCard = (raw) => raw.split("").map((char, index) => (index < 6 || (raw.length === 16 && index >= 14) ? char : "*")).join("").replace(/(.{4})/g, "$1 ").trim();
    const maskExpiry = (raw) => (raw.length > 2 ? `**/${"*".repeat(raw.length - 2)}` : "*".repeat(raw.length));
    const formatExpiry = (value) => {
        const raw = digits(value).slice(0, 4);
        return raw.length > 2 ? `${raw.slice(0, 2)}/${raw.slice(2)}` : raw;
    };

    const submit = async (event) => {
        event.preventDefault();
        setError("");
        if (!form.firstName.trim() || !form.lastName.trim()) return setError("Ad ve soyad zorunlu.");
        if (!/^(\+90|0)?\s?\(?5\d{2}\)?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}$/.test(form.phone.trim())) return setError("Geçerli bir telefon numarası girin (05xx xxx xx xx).");
        if (!form.city || !form.district || !form.neighborhood || !form.address.trim()) return setError("İl, ilçe, mahalle ve açık adres zorunlu.");
        if (!form.cardHolder.trim()) return setError("Kart üzerindeki ismi girin.");
        if (digits(form.cardNumber).length !== 16) return setError("Kart numarası 16 haneli olmalı.");
        const [month, year] = form.expiry.split("/");
        if (!month || !year || year.length !== 2 || Number(month) < 1 || Number(month) > 12) return setError("Son kullanma tarihi AA/YY biçiminde olmalı.");
        const now = new Date();
        if (2000 + Number(year) < now.getFullYear() || (2000 + Number(year) === now.getFullYear() && Number(month) < now.getMonth() + 1)) return setError("Kartın son kullanma tarihi geçmiş.");
        if (form.cvv.length !== 3) return setError("CVV 3 haneli olmalı.");
        // Kart bilgisi sunucuya gönderilmez; sadece teslimat bilgisi ve ürünler kaydedilir.
        setSaving(true);
        try {
            const response = await apiFetch("/api/orders", {
                method: "POST",
                body: JSON.stringify({
                    firstName: form.firstName.trim(),
                    lastName: form.lastName.trim(),
                    phone: form.phone.trim(),
                    city: form.city.trim(),
                    district: form.district.trim(),
                    neighborhood: form.neighborhood,
                    address: form.address.trim(),
                    items: items.map((item) => ({ productId: item.id, quantity: item.quantity })),
                }),
            });
            if (!response.ok) {
                const body = await response.json().catch(() => null);
                throw new Error(body?.message || "Sipariş kaydedilemedi.");
            }
        } catch (saveError) {
            setSaving(false);
            return setError(saveError.message || "Sipariş kaydedilemedi.");
        }
        setSaving(false);
        clearRemoved();
        localStorage.setItem("cart", "[]");
        window.dispatchEvent(new Event("cart-changed"));
        setDone(true);
    };

    const header = (
        <header className="store-header">
            <a className="store-logo" href="/home"><span className="brand-mark">E</span> E-Ticaret</a>
            <div style={{ flex: 1 }} />
            <button className="button button-quiet button-small" onClick={() => navigate("/cart")}>← Sepete dön</button>
        </header>
    );

    if (done) {
        return (
            <div className="storefront">
                {header}
                <main className="store-content">
                    <div className="surface empty-state">
                        <h3>Siparişiniz alındı 🎉</h3>
                        <p className="page-subtitle">Teşekkürler {form.firstName}! Siparişiniz hazırlanıyor.</p>
                        <Link className="button button-primary" to="/home">Alışverişe devam et</Link>
                    </div>
                </main>
            </div>
        );
    }

    if (items.length === 0) {
        return (
            <div className="storefront">
                {header}
                <main className="store-content">
                    <div className="surface empty-state">
                        <h3>Sepetiniz boş</h3>
                        <Link className="button button-primary" to="/home">Alışverişe başla</Link>
                    </div>
                </main>
            </div>
        );
    }

    return (
        <div className="storefront">
            {header}
            <main className="store-content">
                <h1 className="page-title">Satın al</h1>
                <form className="checkout-layout" noValidate onSubmit={submit}>
                    <div className="checkout-forms">
                        <section className="surface surface-pad">
                            <h2 className="section-title">Teslimat bilgileri</h2>
                            <div className="form-grid">
                                <label className="field">
                                    <span className="field-label">Ad</span>
                                    <input className="input" required maxLength={60} autoComplete="given-name" value={form.firstName} onChange={(event) => update("firstName", lettersOnly(event.target.value))} />
                                </label>
                                <label className="field">
                                    <span className="field-label">Soyad</span>
                                    <input className="input" required maxLength={60} autoComplete="family-name" value={form.lastName} onChange={(event) => update("lastName", lettersOnly(event.target.value))} />
                                </label>
                                <label className="field span-2">
                                    <span className="field-label">Telefon</span>
                                    <input className="input" required type="tel" maxLength={14} autoComplete="tel" placeholder="05xx xxx xx xx" value={form.phone} onChange={(event) => update("phone", phoneChars(event.target.value))} />
                                </label>
                                <label className="field">
                                    <span className="field-label">İl</span>
                                    <select className="input" required value={provinceId} onChange={(event) => pickProvince(event.target.value)}>
                                        <option value="">İl seçin</option>
                                        {provinces.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                                    </select>
                                </label>
                                <label className="field">
                                    <span className="field-label">İlçe</span>
                                    <select className="input" required value={districtId} disabled={!provinceId} onChange={(event) => pickDistrict(event.target.value)}>
                                        <option value="">{provinceId ? "İlçe seçin" : "Önce il seçin"}</option>
                                        {districts.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                                    </select>
                                </label>
                                <label className="field span-2">
                                    <span className="field-label">Mahalle</span>
                                    <select className="input" required value={form.neighborhood} disabled={!districtId} onChange={(event) => update("neighborhood", event.target.value)}>
                                        <option value="">{districtId ? "Mahalle seçin" : "Önce ilçe seçin"}</option>
                                        {neighborhoods.map((item) => <option key={item.id} value={item.name}>{item.name}</option>)}
                                    </select>
                                </label>
                                {addressLoadFailed && <p className="error-banner span-2" role="alert">Adres listesi yüklenemedi. İnternet bağlantınızı kontrol edip sayfayı yenileyin.</p>}
                                <label className="field span-2">
                                    <span className="field-label">Açık adres</span>
                                    <textarea className="input" required rows={3} maxLength={300} autoComplete="street-address" value={form.address} onChange={(event) => update("address", event.target.value)} />
                                </label>
                            </div>
                        </section>

                        <section className="surface surface-pad">
                            <h2 className="section-title">Ödeme bilgileri</h2>
                            <div className="form-grid">
                                <label className="field span-2">
                                    <span className="field-label">Kart üzerindeki isim</span>
                                    <input className="input" required maxLength={80} autoComplete="cc-name" value={form.cardHolder} onChange={(event) => update("cardHolder", lettersOnly(event.target.value))} />
                                </label>
                                <label className="field span-2">
                                    <span className="field-label">Kart numarası</span>
                                    <input className="input" required inputMode="numeric" autoComplete="cc-number" placeholder="0000 0000 0000 0000" value={maskCard(digits(form.cardNumber))} onChange={(event) => update("cardNumber", reconcile(digits(form.cardNumber), event.target.value, 16))} />
                                </label>
                                <label className="field">
                                    <span className="field-label">Son kullanma (AA/YY)</span>
                                    <input className="input" required inputMode="numeric" autoComplete="cc-exp" placeholder="AA/YY" value={maskExpiry(digits(form.expiry))} onChange={(event) => update("expiry", formatExpiry(reconcile(digits(form.expiry), event.target.value, 4)))} />
                                </label>
                                <label className="field">
                                    <span className="field-label">CVV</span>
                                    <input className="input" required inputMode="numeric" autoComplete="cc-csc" maxLength={3} placeholder="123" value={"*".repeat(form.cvv.length)} onChange={(event) => update("cvv", reconcile(form.cvv, event.target.value, 3))} />
                                </label>
                            </div>
                        </section>
                    </div>

                    <aside className="surface surface-pad checkout-summary">
                        <h2 className="section-title">Sipariş özeti</h2>
                        {items.map((item) => (
                            <div className="checkout-line" key={item.id}>
                                <span>{item.name} × {item.quantity}</span>
                                <b>{money(Number(item.price) * item.quantity)}</b>
                            </div>
                        ))}
                        <div className="checkout-line checkout-total"><span>Toplam</span><b>{money(total)}</b></div>
                        {error && <p className="error-banner" role="alert">{error}</p>}
                        <button className="button button-primary" type="submit" disabled={saving}>{saving ? "Kaydediliyor..." : "Siparişi tamamla"}</button>
                    </aside>
                </form>
            </main>
        </div>
    );
}
