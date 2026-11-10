import { NavLink, useNavigate } from "react-router-dom";
import { clearSessionStorage } from "../favorites";

const navigation = [
    { to: "/admin", label: "Genel Bakış", icon: "⌂", end: true },
    { to: "/admin/products", label: "Ürünler", icon: "▦" },
    { to: "/admin/category", label: "Kategoriler", icon: "◈" },
    { to: "/admin/brand", label: "Markalar", icon: "✧" },
    { to: "/admin/orders", label: "Siparişler", icon: "▤" },
    { to: "/admin/users", label: "Kullanıcılar", icon: "♙" },
];

export default function AdminLayout({ children }) {
    const navigate = useNavigate();
    const username = localStorage.getItem("username") || "Yönetici";
    const initials = username.slice(0, 2);

    const logout = () => {
        clearSessionStorage();
        navigate("/login");
    };

    return (
        <div className="admin-shell">
            <aside className="admin-sidebar">
                <a className="brand-lockup" href="/admin">
                    <span className="brand-mark">E</span>
                    <span>
                        <span className="brand-name">E-Ticaret</span>
                        <span className="brand-caption">Yönetim merkezi</span>
                    </span>
                </a>
                <div className="nav-caption">MENÜ</div>
                <nav className="side-nav" aria-label="Yönetim menüsü">
                    {navigation.map((item) => (
                        <NavLink
                            key={item.to}
                            to={item.to}
                            end={item.end}
                            className={({ isActive }) => `side-link${isActive ? " active" : ""}`}
                        >
                            <span className="side-icon" aria-hidden="true">{item.icon}</span>
                            {item.label}
                        </NavLink>
                    ))}
                </nav>
                <div className="side-footer">
                    <div className="user-chip">
                        <span className="avatar">{initials}</span>
                        <span style={{ minWidth: 0 }}>
                            <span className="user-chip-name">{username}</span>
                            <span className="user-chip-role">Yönetici</span>
                        </span>
                    </div>
                </div>
            </aside>
            <div className="admin-main">
                <header className="admin-topbar">
                    <span className="topbar-context">E-Ticaret <span aria-hidden="true">/</span> Yönetim</span>
                    <div className="topbar-actions">
                        <span className="topbar-user"><span className="avatar small">{initials}</span>{username}</span>
                        <button className="button button-primary button-small" type="button" onClick={() => navigate("/home")}>
                            Kullanıcı görünümü
                        </button>
                        <button className="button button-quiet button-small" type="button" onClick={logout}>
                            Çıkış yap
                        </button>
                    </div>
                </header>
                <main className="admin-content">{children}</main>
            </div>
        </div>
    );
}
