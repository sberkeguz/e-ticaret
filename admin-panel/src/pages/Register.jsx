import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";

export default function Register() {
    const [username, setUsername] = useState("");
    const [password, setPassword] = useState("");
    const [error, setError] = useState("");
    const navigate = useNavigate();

    const handleRegister = async (e) => {
        e.preventDefault();
        setError("");

        try {
            const res = await fetch("http://localhost:8080/api/auth/register", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ username, password }),
            });

            if (!res.ok) {
                setError("Kayıt başarısız. Bu kullanıcı adı alınmış olabilir.");
                return;
            }

            navigate("/login");
        } catch {
            setError("Sunucuya ulaşılamadı.");
        }
    };

    return (
        <form onSubmit={handleRegister}>
            <h2>Kayıt Ol</h2>
            <input placeholder="Kullanıcı Adı" value={username}
                   onChange={(e) => setUsername(e.target.value)} />
            <input type="password" placeholder="Şifre" value={password}
                   onChange={(e) => setPassword(e.target.value)} />
            <button type="submit">Kayıt Ol</button>
            {error && <p style={{ color: "red" }}>{error}</p>}
            <p>Zaten hesabın var mı? <Link to="/login">Giriş yap</Link></p>
        </form>
    );
}