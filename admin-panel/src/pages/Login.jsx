import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";

export default function Login() {
    const [username, setUsername] = useState("");
    const [password, setPassword] = useState("");
    const [error, setError] = useState("");
    const navigate = useNavigate();

    const handleLogin = async (e) => {
        e.preventDefault();
        setError("");

        try {
            const res = await fetch("http://localhost:8080/api/auth/login", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ username, password }),
            });

            if (!res.ok) {
                setError("Kullanıcı adı veya şifre hatalı.");
                return;
            }

            // Token header'da, rol ve kullanıcı adı body'de geliyor
            const authHeader = res.headers.get("Authorization");
            const token = authHeader ? authHeader.replace("Bearer ", "") : null;
            const data = await res.json();

            localStorage.setItem("token", token);
            localStorage.setItem("role", data.role);
            localStorage.setItem("username", data.username);

            // Role göre yönlendir
            navigate(data.role === "ADMIN" ? "/admin" : "/home");
        } catch {
            setError("Sunucuya ulaşılamadı.");
        }
    };

    return (
        <form onSubmit={handleLogin}>
            <h2>Giriş Yap</h2>
            <input placeholder="Kullanıcı Adı" value={username}
                   onChange={(e) => setUsername(e.target.value)} />
            <input type="password" placeholder="Şifre" value={password}
                   onChange={(e) => setPassword(e.target.value)} />
            <button type="submit">Giriş Yap</button>
            {error && <p style={{ color: "red" }}>{error}</p>}
            <p>Hesabın yok mu? <Link to="/register">Kayıt ol</Link></p>
        </form>
    );
}