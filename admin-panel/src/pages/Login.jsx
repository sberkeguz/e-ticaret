import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";

export default function Login() {
    const [username, setUsername] = useState("");
    const [password, setPassword] = useState("");
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(false);
    const navigate = useNavigate();

    const handleLogin = async (event) => {
        event.preventDefault();
        setError("");
        setLoading(true);
        try {
            const response = await fetch("/api/auth/login", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ username, password }),
            });
            if (!response.ok) {
                const body = await response.json().catch(() => null);
                setError(body?.message || "Giriş başarısız. Bilgilerinizi kontrol edin.");
                return;
            }
            const authHeader = response.headers.get("Authorization");
            const token = authHeader ? authHeader.replace("Bearer ", "") : null;
            const data = await response.json();
            if (token) localStorage.setItem("token", token);
            localStorage.setItem("role", data.role);
            localStorage.setItem("username", data.username);
            navigate(data.role === "ADMIN" ? "/admin" : "/home");
        } catch {
            setError("Sunucuya ulaşılamadı.");
        } finally {
            setLoading(false);
        }
    };

    return (
        <main className="auth-page">
            <section className="auth-art">
                <a className="brand-lockup" href="/login" style={{ padding: 0 }}>
                    <span className="brand-mark">E</span><span className="brand-name">E-Ticaret</span>
                </a>
                <div className="auth-art-content">
                    <div className="eyebrow" style={{ color: "#cfd3ff" }}>ALIŞVERİŞİN YENİ HALİ</div>
                    <h1>Her güzel keşif bir adımla başlar.</h1>
                    <p>Mağazamızdaki seçkin ürünlere ulaşmak ve size özel deneyiminize devam etmek için hesabınıza giriş yapın.</p>
                </div>
                <div className="auth-art-foot">© 2026 E-Ticaret · Güvenli alışveriş deneyimi</div>
            </section>
            <section className="auth-form-side">
                <form className="auth-form" onSubmit={handleLogin}>
                    <div className="eyebrow">TEKRAR HOŞ GELDİNİZ</div>
                    <h2>Hesabınıza giriş yapın</h2>
                    <p className="auth-form-intro">Devam etmek için kullanıcı adı ve şifrenizi girin.</p>
                    {error && <p className="error-banner" role="alert">{error}</p>}
                    <div className="auth-fields">
                        <label className="field">
                            <span className="field-label">Kullanıcı adı</span>
                            <input className="input" autoComplete="username" required value={username} onChange={(event) => setUsername(event.target.value)} placeholder="Kullanıcı adınız" />
                        </label>
                        <label className="field">
                            <span className="field-label">Şifre</span>
                            <input className="input" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} placeholder="Şifreniz" />
                        </label>
                        <button className="button button-primary" type="submit" disabled={loading} style={{ width: "100%", minHeight: 47, marginTop: 5 }}>
                            {loading ? "Giriş yapılıyor…" : "Giriş yap  →"}
                        </button>
                    </div>
                    <p className="auth-switch">Henüz hesabınız yok mu? <Link to="/register">Hesap oluşturun</Link></p>
                </form>
            </section>
        </main>
    );
}
