import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";

export default function Register() {
    const [username, setUsername] = useState("");
    const [password, setPassword] = useState("");
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(false);
    const navigate = useNavigate();

    const handleRegister = async (event) => {
        event.preventDefault();
        setError("");
        setLoading(true);
        try {
            const response = await fetch("/api/auth/register", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ username, password }),
            });
            if (!response.ok) {
                const body = await response.json().catch(() => null);
                setError(body?.message || "Kayıt başarısız.");
                return;
            }
            navigate("/login");
        } catch {
            setError("Sunucuya ulaşılamadı.");
        } finally {
            setLoading(false);
        }
    };

    return (
        <main className="auth-page">
            <section className="auth-art">
                <a className="brand-lockup" href="/register" style={{ padding: 0 }}>
                    <span className="brand-mark">E</span><span className="brand-name">E-Ticaret</span>
                </a>
                <div className="auth-art-content">
                    <div className="eyebrow" style={{ color: "#cfd3ff" }}>YENİ BİR BAŞLANGIÇ</div>
                    <h1>Keşiflerinize yer açın.</h1>
                    <p>Hesabınızı oluşturun, mağazamızdaki ürünleri keşfetmeye hemen başlayın.</p>
                </div>
                <div className="auth-art-foot">© 2026 E-Ticaret · Alışverişin keyifli hali</div>
            </section>
            <section className="auth-form-side">
                <form className="auth-form" onSubmit={handleRegister}>
                    <div className="eyebrow">ÜCRETSİZ HESAP</div>
                    <h2>Aramıza katılın</h2>
                    <p className="auth-form-intro">Birkaç bilgiyle hesabınızı oluşturup alışverişe başlayın.</p>
                    {error && <p className="error-banner" role="alert">{error}</p>}
                    <div className="auth-fields">
                        <label className="field">
                            <span className="field-label">Kullanıcı adı</span>
                            <input className="input" autoComplete="username" required value={username} onChange={(event) => setUsername(event.target.value)} placeholder="Bir kullanıcı adı seçin" />
                        </label>
                        <label className="field">
                            <span className="field-label">Şifre</span>
                            <input className="input" type="password" autoComplete="new-password" minLength={8} required value={password} onChange={(event) => setPassword(event.target.value)} placeholder="En az 8 karakter, harf ve rakam" />
                        </label>
                        <button className="button button-primary" type="submit" disabled={loading} style={{ width: "100%", minHeight: 47, marginTop: 5 }}>
                            {loading ? "Hesap oluşturuluyor…" : "Hesap oluştur  →"}
                        </button>
                    </div>
                    <p className="auth-switch">Zaten hesabınız var mı? <Link to="/login">Giriş yapın</Link></p>
                </form>
            </section>
        </main>
    );
}
