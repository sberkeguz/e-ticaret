import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { apiFetch } from "../api";
import { getCart, syncCart } from "../cart";
import StoreHeader from "../components/StoreHeader";
import useLiveCart from "../useLiveCart";
import { CartNotice } from "./Cart";

const money = (value) => Number(value || 0).toLocaleString("tr-TR", { style: "currency", currency: "TRY" });
const ADDRESS_API = "https://api.turkiyeapi.dev/v1";
const loadList = (path) => fetch(`${ADDRESS_API}/${path}`)
    .then((response) => response.json())
    .then((body) => (body.data ?? []).map(({ id, name }) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name, "tr")));
const digits = (value) => value.replace(/\D/g, "");

const blankCard = { holderName: "", number: "", expireMonth: "", expireYear: "", cvc: "" };
const currentYear = new Date().getFullYear();
const expireYears = Array.from({ length: 16 }, (_, index) => String(currentYear + index));
const expireMonths = Array.from({ length: 12 }, (_, index) => String(index + 1).padStart(2, "0"));
const groupCardNumber = (value) => digits(value).slice(0, 19).replace(/(\d{4})(?=\d)/g, "$1 ");
const luhnValid = (number) => {
    let sum = 0;
    for (let index = 0; index < number.length; index += 1) {
        let digit = Number(number[number.length - 1 - index]);
        if (index % 2 === 1) { digit *= 2; if (digit > 9) digit -= 9; }
        sum += digit;
    }
    return number.length >= 15 && sum % 10 === 0;
};
export const PAYMENT_REASON_KEY = "payment-reason";
// Ödeme genelde 0,5 sn içinde biter: ilk soru erken, sonrakiler saniyede bir.
const PROCESSING_FIRST_CHECK_MS = 500;
const PROCESSING_CHECK_MS = 1000;
const PROCESSING_MAX_CHECKS = 90; // ~90 sn; sonrasında Siparişlerim'e yönlendirilir
const PAYMENT_RESULTS = ["success", "failed", "review", "pending", "declined", "error"];

const blankForm = {
    firstName: "", lastName: "", phone: "", email: "", identityNumber: "",
    city: "", district: "", neighborhood: "", address: "",
};

export default function Checkout() {
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const payOrderId = searchParams.get("order");
    const [payOrder, setPayOrder] = useState(null);
    const [items, setItems] = useState(() => (payOrderId ? [] : getCart()));
    const [stockLoading, setStockLoading] = useState(true);
    const [stockError, setStockError] = useState("");
    const [form, setForm] = useState(blankForm);
    const [error, setError] = useState("");
    const [saving, setSaving] = useState(false);
    const [threeDSHtml, setThreeDSHtml] = useState("");
    // 2D ödeme kabul edildi, sonucu bekleniyor: bu siparişin durumunu soruyoruz.
    const [processingOrderId, setProcessingOrderId] = useState(null);
    const [card, setCard] = useState(blankCard);
    const threeDSFrame = useRef(null);
    // Her ödeme denemesinin anahtarı. Cevap gelmeden bağlantı koparsa tekrar gönderimde aynı anahtar
    // gider; sunucu bunu aynı deneme sayar ve kartı ikinci kez çekmez. Cevap gelince yeni anahtar üretilir.
    const paymentKey = useRef(crypto.randomUUID());
    // Ödeme sürerken sepet yenilenmez; diğer zamanlarda başka biri ürünü bitirirse anında görülür.
    const [cartNotice, clearCartNotice] = useLiveCart(!payOrderId && !saving && !threeDSHtml && !processingOrderId);
    const [forceThreeDS, setForceThreeDS] = useState(false);
    const [provinces, setProvinces] = useState([]);
    const [districts, setDistricts] = useState([]);
    const [neighborhoods, setNeighborhoods] = useState([]);
    const [provinceId, setProvinceId] = useState("");
    const [districtId, setDistrictId] = useState("");
    const [addressLoadFailed, setAddressLoadFailed] = useState(false);
    const [profileAddress, setProfileAddress] = useState(null);

    useEffect(() => {
        if (!payOrderId) return undefined;
        let active = true;
        apiFetch("/api/orders")
            .then(async (response) => {
                if (!response.ok) throw new Error("Sipariş yüklenemedi.");
                const order = (await response.json()).find((item) => String(item.id) === payOrderId);
                if (!order) throw new Error("Sipariş bulunamadı.");
                if (order.status !== "Ödeme bekliyor") throw new Error("Bu siparişin ödemesi artık beklemede değil.");
                if (!active) return;
                setPayOrder(order);
                setItems(order.items.map((item) => ({
                    id: item.productId, name: item.productName, price: item.price,
                    quantity: item.quantity, stock: item.quantity,
                })));
            })
            .catch((loadError) => { if (active) setStockError(loadError.message || "Sipariş yüklenemedi."); })
            .finally(() => { if (active) setStockLoading(false); });
        return () => { active = false; };
    }, [payOrderId]);

    useEffect(() => {
        if (payOrderId) return undefined;
        let active = true;
        const refresh = () => setItems(getCart());
        window.addEventListener("cart-changed", refresh);
        syncCart()
            .catch((loadError) => { if (active) setStockError(loadError.message || "Sepet stokları kontrol edilemedi."); })
            .finally(() => { if (active) setStockLoading(false); });
        return () => { active = false; window.removeEventListener("cart-changed", refresh); };
    }, []);

    useEffect(() => {
        loadList("provinces?fields=id,name&limit=100").then(setProvinces).catch(() => setAddressLoadFailed(true));
    }, []);

    useEffect(() => {
        let active = true;
        apiFetch("/api/profile")
            .then(async (response) => {
                if (!response.ok) throw new Error("Profil bilgileri yüklenemedi.");
                const profile = await response.json();
                if (!active) return;
                setForm((current) => ({
                    ...current,
                    firstName: profile.firstName || current.firstName,
                    lastName: profile.lastName || current.lastName,
                    phone: profile.phone || current.phone,
                    city: profile.city || current.city,
                    district: profile.district || current.district,
                    neighborhood: profile.neighborhood || current.neighborhood,
                    address: profile.address || current.address,
                }));
                setProfileAddress(profile);
            })
            .catch((loadError) => { if (active) setError(loadError.message || "Profil bilgileri yüklenemedi."); });
        return () => { active = false; };
    }, []);

    useEffect(() => {
        if (!profileAddress?.city || provinces.length === 0) return undefined;
        let active = true;
        const province = provinces.find((item) => item.name === profileAddress.city);
        if (!province) return undefined;

        const loadSavedAddress = async () => {
            setProvinceId(String(province.id));
            try {
                const nextDistricts = await loadList(`districts?provinceId=${province.id}&fields=id,name&limit=100`);
                if (!active) return;
                setDistricts(nextDistricts);
                const district = nextDistricts.find((item) => item.name === profileAddress.district);
                if (!district) return;
                setDistrictId(String(district.id));
                const nextNeighborhoods = await loadList(`neighborhoods?districtId=${district.id}&fields=id,name&limit=1000`);
                if (active) setNeighborhoods(nextNeighborhoods);
            } catch {
                if (active) setAddressLoadFailed(true);
            }
        };
        loadSavedAddress();
        return () => { active = false; };
    }, [profileAddress, provinces]);

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
    const unavailable = items.filter((item) => item.quantity > Number(item.stock ?? 0));
    const lettersOnly = (value) => value.replace(/[^\p{L}\s'.-]/gu, "");
    const phoneChars = (value) => {
        const raw = digits(value).slice(0, 11);
        return [raw.slice(0, 4), raw.slice(4, 7), raw.slice(7, 9), raw.slice(9, 11)].filter(Boolean).join(" ");
    };
    const update = (field, value) => setForm((current) => ({ ...current, [field]: value }));

    const updateCard = (field, value) => setCard((current) => ({ ...current, [field]: value }));

    const cardError = () => {
        const number = digits(card.number);
        if (!card.holderName.trim()) return "Kart üzerindeki ismi girin.";
        if (!luhnValid(number)) return "Kart numarası geçersiz.";
        if (!card.expireMonth || !card.expireYear) return "Son kullanma tarihini seçin.";
        const now = new Date();
        if (Number(card.expireYear) === now.getFullYear() && Number(card.expireMonth) < now.getMonth() + 1) return "Kartın son kullanma tarihi geçmiş.";
        if (!/^\d{3,4}$/.test(card.cvc)) return "CVC 3 veya 4 haneli olmalı.";
        return "";
    };

    const submit = async (event) => {
        event.preventDefault();
        if (saving || threeDSHtml || processingOrderId) return;
        setError("");
        if (stockLoading || stockError) return setError(stockError || "Sepet stokları doğrulanamadı. Lütfen tekrar deneyin.");
        if (unavailable.length) return setError("Sepetinizde yeterli stoğu olmayan ürünler var. Sipariş vermek için sepetinizi düzenleyin.");
        if (!payOrderId) {
            if (!form.firstName.trim() || !form.lastName.trim()) return setError("Ad ve soyad zorunlu.");
            if (!/^(\+90|0)?\s?\(?5\d{2}\)?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}$/.test(form.phone.trim())) return setError("Geçerli bir telefon numarası girin (05xx xxx xx xx).");
        }
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) return setError("Geçerli bir e-posta adresi girin.");
        if (!/^\d{11}$/.test(form.identityNumber)) return setError("T.C. kimlik numarası 11 haneli olmalı.");
        if (!payOrderId && (!form.city || !form.district || !form.neighborhood || !form.address.trim())) return setError("İl, ilçe, mahalle ve açık adres zorunlu.");
        const invalidCard = cardError();
        if (invalidCard) return setError(invalidCard);

        const cardBody = {
            holderName: card.holderName.trim(),
            number: digits(card.number),
            expireMonth: card.expireMonth,
            expireYear: card.expireYear,
            cvc: card.cvc,
        };
        const body = payOrderId
            ? { email: form.email.trim(), identityNumber: form.identityNumber, forceThreeDS, card: cardBody }
            : {
                firstName: form.firstName.trim(),
                lastName: form.lastName.trim(),
                phone: form.phone.trim(),
                email: form.email.trim(),
                identityNumber: form.identityNumber,
                forceThreeDS,
                city: form.city.trim(),
                district: form.district.trim(),
                neighborhood: form.neighborhood,
                address: form.address.trim(),
                items: items.map((item) => ({ productId: item.id, quantity: item.quantity })),
                card: cardBody,
            };

        setSaving(true);
        try {
            let response;
            try {
                response = await apiFetch(payOrderId ? `/api/payments/orders/${encodeURIComponent(payOrderId)}/pay` : "/api/payments/checkout", {
                    method: "POST",
                    headers: { "Idempotency-Key": paymentKey.current },
                    body: JSON.stringify(body),
                });
            } catch {
                // Cevap gelmedi: ödeme alınmış olabilir. Anahtar aynı kalır, tekrar denemek güvenli.
                throw new Error("Bağlantı kesildi. Siparişlerim sayfasını kontrol edin veya aynı bilgilerle tekrar deneyin.");
            }
            paymentKey.current = crypto.randomUUID();
            const result = await response.json().catch(() => null);
            if (!response.ok) throw new Error(result?.message || "Ödeme alınamadı.");
            setCard((current) => ({ ...current, number: "", cvc: "" }));
            if (result?.result === "processing" && result.orderId) {
                setProcessingOrderId(result.orderId);
                setSaving(false);
                return;
            }
            if (result?.result === "threeds" && result.threeDSHtmlContent) {
                setThreeDSHtml(result.threeDSHtmlContent);
                setSaving(false);
                return;
            }
            const outcome = PAYMENT_RESULTS.includes(result?.result) ? result.result : "pending";
            navigate(`/orders?payment=${outcome}`, { replace: true });
        } catch (saveError) {
            setSaving(false);
            setError(saveError.message || "Ödeme alınamadı.");
        }
    };

    // 3D Secure bitince iyzico, backend'deki dönüş sayfasını (ör. ngrok adresi) iframe'de açar.
    // O sayfa sonucu postMessage ile bildirir; yalnızca kendi iframe'imizden gelen mesajı kabul ederiz.
    useEffect(() => {
        if (!threeDSHtml) return undefined;
        const onMessage = (event) => {
            if (!threeDSFrame.current || event.source !== threeDSFrame.current.contentWindow) return;
            const data = event.data;
            if (data?.type !== "iyzico-payment-result" || !PAYMENT_RESULTS.includes(data.result)) return;
            navigate(`/orders?payment=${encodeURIComponent(data.result)}`, { replace: true });
        };
        window.addEventListener("message", onMessage);
        return () => window.removeEventListener("message", onMessage);
    }, [threeDSHtml, navigate]);

    const handlePaymentFrameLoad = (event) => {
        let location;
        try {
            location = event.currentTarget.contentWindow.location;
            if (location.origin !== window.location.origin || location.pathname !== "/orders") return;
            const result = new URLSearchParams(location.search).get("payment");
            if (PAYMENT_RESULTS.includes(result)) {
                navigate(`/orders?payment=${encodeURIComponent(result)}`, { replace: true });
            }
        } catch (loadError) {
            if (loadError.name !== "SecurityError") throw loadError;
        }
    };

    const header = (
        <StoreHeader syncCartOnMount={false} />
    );
    // "Ödemeniz işleniyor…": sonuç gelene kadar sunucuya sorar (genelde birkaç saniye).
    useEffect(() => {
        if (!processingOrderId) return undefined;
        let active = true;
        let tries = 0;
        const check = async () => {
            tries += 1;
            try {
                const response = await apiFetch(`/api/payments/orders/${encodeURIComponent(processingOrderId)}/status`);
                const status = response.ok ? await response.json() : null;
                if (!active) return;
                if (status && ["success", "review", "declined", "failed"].includes(status.result)) {
                    // Sebep adrese yazılmaz (sahte linkle sahte mesaj gösterilemesin); bu sekmenin hafızasında taşınır.
                    try {
                        if (status.message) sessionStorage.setItem(PAYMENT_REASON_KEY, status.message);
                        else sessionStorage.removeItem(PAYMENT_REASON_KEY);
                    } catch { /* gizli sekme vb.: sebepsiz devam */ }
                    navigate(`/orders?payment=${status.result}`, { replace: true });
                    return;
                }
            } catch {
                // Bağlantı anlık koptuysa bir sonraki denemede tekrar sorarız.
            }
            if (!active) return;
            if (tries >= PROCESSING_MAX_CHECKS) {
                navigate("/orders?payment=pending", { replace: true });
                return;
            }
            timer = setTimeout(check, PROCESSING_CHECK_MS);
        };
        let timer = setTimeout(check, PROCESSING_FIRST_CHECK_MS);
        return () => { active = false; clearTimeout(timer); };
    }, [processingOrderId, navigate]);

    const locked = saving || Boolean(threeDSHtml) || Boolean(processingOrderId);

    if (!payOrderId && items.length === 0) {
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

    const contactFields = (
        <>
            <label className="field">
                <span className="field-label">E-posta</span>
                <input className="input" required type="email" maxLength={254} autoComplete="email" value={form.email} onChange={(event) => update("email", event.target.value)} />
            </label>
            <label className="field">
                <span className="field-label">T.C. kimlik numarası</span>
                <input className="input" required inputMode="numeric" autoComplete="off" maxLength={11} value={form.identityNumber} onChange={(event) => update("identityNumber", digits(event.target.value).slice(0, 11))} />
            </label>
        </>
    );

    return (
        <div className="storefront">
            {header}
            <main className="store-content">
                <h1 className="page-title">{payOrderId ? "Siparişi öde" : "Satın al"}</h1>
                {cartNotice && <CartNotice text={cartNotice} onClose={clearCartNotice} />}
                <form className="checkout-layout" noValidate onSubmit={submit}>
                    <div className="checkout-forms">
                        <section className="surface surface-pad">
                            <h2 className="section-title">Teslimat bilgileri</h2>
                            {payOrderId ? (
                                <fieldset className="checkout-delivery-fields" disabled={locked}>
                                    {payOrder && (
                                        <p className="section-caption">
                                            {payOrder.firstName} {payOrder.lastName}, {payOrder.phone}<br />
                                            {payOrder.neighborhood} Mah. {payOrder.address}, {payOrder.district}/{payOrder.city}
                                        </p>
                                    )}
                                    <div className="form-grid">{contactFields}</div>
                                </fieldset>
                            ) : (
                            <fieldset className="checkout-delivery-fields" disabled={locked}>
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
                                {contactFields}
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
                            </fieldset>
                            )}
                        </section>

                        <section className="surface surface-pad">
                            <h2 className="section-title">Kart bilgileri</h2>
                            <p className="page-subtitle">Kart bilgileriniz yalnızca ödeme için iyzico'ya iletilir; bu sitede saklanmaz.</p>
                            {processingOrderId ? (
                                <div className="processing-box" role="status" aria-live="polite">
                                    <span className="spinner" aria-hidden="true" />
                                    <div>
                                        <strong>Ödemeniz işleniyor…</strong>
                                        <p>Bankanızdan onay bekleniyor, bu genelde birkaç saniye sürer. Lütfen sayfayı kapatmayın.</p>
                                    </div>
                                </div>
                            ) : threeDSHtml ? (
                                <>
                                    <p className="section-caption">Bankanızın 3D Secure doğrulamasını tamamlayın.</p>
                                    <iframe ref={threeDSFrame} className="checkout-payment-frame" title="3D Secure doğrulama"
                                        srcDoc={threeDSHtml} onLoad={handlePaymentFrameLoad} />
                                </>
                            ) : (
                                <fieldset className="checkout-card-fields" disabled={locked}>
                                    <div className="form-grid">
                                        <label className="field span-2">
                                            <span className="field-label">Kart üzerindeki isim</span>
                                            <input className="input" required maxLength={100} autoComplete="cc-name" value={card.holderName} onChange={(event) => updateCard("holderName", lettersOnly(event.target.value))} />
                                        </label>
                                        <label className="field span-2">
                                            <span className="field-label">Kart numarası</span>
                                            <input className="input" required inputMode="numeric" autoComplete="cc-number" placeholder="0000 0000 0000 0000" maxLength={23} value={card.number} onChange={(event) => updateCard("number", groupCardNumber(event.target.value))} />
                                        </label>
                                        <div className="field">
                                            <span className="field-label">Son kullanma tarihi</span>
                                            <div className="checkout-expiry">
                                                <select className="input" required aria-label="Ay" autoComplete="cc-exp-month" value={card.expireMonth} onChange={(event) => updateCard("expireMonth", event.target.value)}>
                                                    <option value="">Ay</option>
                                                    {expireMonths.map((month) => <option key={month} value={month}>{month}</option>)}
                                                </select>
                                                <select className="input" required aria-label="Yıl" autoComplete="cc-exp-year" value={card.expireYear} onChange={(event) => updateCard("expireYear", event.target.value)}>
                                                    <option value="">Yıl</option>
                                                    {expireYears.map((year) => <option key={year} value={year}>{year}</option>)}
                                                </select>
                                            </div>
                                        </div>
                                        <label className="field">
                                            <span className="field-label">CVC</span>
                                            <input className="input" required inputMode="numeric" autoComplete="cc-csc" maxLength={4} value={card.cvc} onChange={(event) => updateCard("cvc", digits(event.target.value).slice(0, 4))} />
                                        </label>
                                    </div>
                                </fieldset>
                            )}
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
                        {stockLoading && <p>{payOrderId ? "Sipariş yükleniyor…" : "Stoklar kontrol ediliyor…"}</p>}
                        {stockError && <p className="error-banner" role="alert">{stockError}</p>}
                        {!stockLoading && unavailable.length > 0 && <p className="error-banner" role="alert">Yeterli stok yok: {unavailable.map((item) => item.name).join(", ")}. <Link to="/cart">Sepeti düzenle</Link></p>}
                        <button
                            className={`button ${forceThreeDS ? "button-primary" : "button-quiet"}`}
                            type="button"
                            disabled={locked}
                            aria-pressed={forceThreeDS}
                            onClick={() => setForceThreeDS((enabled) => !enabled)}
                        >
                            3D Secure: {forceThreeDS ? "Açık" : "Kapalı"}
                        </button>
                        <p className="section-caption">Açıkken bankanızın doğrulama ekranı bu sayfada açılır.</p>
                        {error && <p className="error-banner" role="alert">{error}</p>}
                        <button className="button button-primary" type="submit" disabled={locked || stockLoading || Boolean(stockError) || unavailable.length > 0}>
                            {saving ? "Ödeme alınıyor..." : processingOrderId ? "Ödemeniz işleniyor…" : threeDSHtml ? "Banka doğrulaması bekleniyor" : `${money(total)} öde`}
                        </button>
                    </aside>
                </form>
            </main>
        </div>
    );
}
