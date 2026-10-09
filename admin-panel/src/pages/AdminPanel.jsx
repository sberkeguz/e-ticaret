import { Link, useNavigate } from "react-router-dom";

export default function AdminPanel() {
    const navigate = useNavigate();
    const username = localStorage.getItem("username");

    const logout = () => {
        localStorage.clear();
        navigate("/login");
    };

    return (
        <div style={styles.page}>
            <header style={styles.header}>
                <h1 style={{ margin: 0 }}>Admin Paneli</h1>
                <div>
                    <span style={{ marginRight: 12 }}>{username}</span>
                    <button onClick={logout}>Çıkış Yap</button>
                </div>
            </header>

            <nav style={styles.menu}>
                <Link to="/admin/brand" style={styles.item}>Markalar</Link>
                <Link to="/admin/category" style={styles.item}>Kategoriler</Link>
                <Link to="/admin/users" style={styles.item}>Kullanıcılar</Link>
            </nav>
        </div>
    );
}

const styles = {
    page: { maxWidth: 800, margin: "0 auto", padding: 24, fontFamily: "system-ui, sans-serif" },
    header: { display: "flex", justifyContent: "space-between", alignItems: "center" },
    menu: { display: "flex", gap: 12, marginTop: 24 },
    item: { padding: "16px 24px", border: "1px solid #ddd", borderRadius: 8, textDecoration: "none", color: "#222" },
};