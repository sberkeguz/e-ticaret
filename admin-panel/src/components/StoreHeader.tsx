import { useEffect, useState, type CSSProperties, type ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import { cartCount, syncCart } from "../cart";
import { clearSessionStorage } from "../favorites";
import { logoutSession } from "../session";

interface StoreHeaderProps {
    /** Başlığın ortasındaki arama kutusu (sayfa verir). */
    search?: ReactNode;
    syncCartOnMount?: boolean;
}

export default function StoreHeader({ search, syncCartOnMount = true }: StoreHeaderProps) {
    const navigate = useNavigate();
    const [username, setUsername] = useState(() => localStorage.getItem("username") || "Misafir");
    const [open, setOpen] = useState(false);
    const [count, setCount] = useState(cartCount);
    const [cartError, setCartError] = useState("");
    const isAdmin = localStorage.getItem("role") === "ADMIN";

    useEffect(() => {
        const updateUsername = () => setUsername(localStorage.getItem("username") || "Misafir");
        window.addEventListener("username-changed", updateUsername);
        return () => window.removeEventListener("username-changed", updateUsername);
    }, []);

    useEffect(() => {
        const updateCount = () => setCount(cartCount());
        const handleSyncError = (event: Event) => setCartError((event as CustomEvent<{ message?: string }>).detail?.message || "Sepet sunucuyla eşitlenemedi.");
        window.addEventListener("cart-changed", updateCount);
        window.addEventListener("cart-sync-error", handleSyncError);
        if (syncCartOnMount) syncCart().catch((error) => setCartError(error.message || "Sepet sunucuyla eşitlenemedi."));
        return () => {
            window.removeEventListener("cart-changed", updateCount);
            window.removeEventListener("cart-sync-error", handleSyncError);
        };
    }, [syncCartOnMount]);

    useEffect(() => {
        if (!open) return undefined;
        const closeOnEscape = (event: KeyboardEvent) => {
            if (event.key === "Escape") setOpen(false);
        };
        window.addEventListener("keydown", closeOnEscape);
        return () => window.removeEventListener("keydown", closeOnEscape);
    }, [open]);

    const logout = async () => {
        await logoutSession();
        clearSessionStorage();
        navigate("/login");
    };

    return (
        <>
            <header className="store-header">
                <Link className="store-logo" to="/home"><span className="brand-mark">E</span> E-Ticaret</Link>
                {search || <div className="store-header-spacer" />}
                <button type="button" className="store-menu-trigger" aria-label="Hesap menüsünü aç" aria-expanded={open} onClick={() => setOpen(true)}>
                    <span aria-hidden="true">☰</span>
                </button>
            </header>
            {cartError && <p className="error-banner" role="alert">{cartError}</p>}
            {open && (
                <div
                    role="presentation"
                    onMouseDown={(event) => {
                        if (event.target === event.currentTarget) setOpen(false);
                    }}
                    style={styles.backdrop}
                >
                    <aside role="dialog" aria-modal="true" aria-label="Hesap menüsü" style={styles.drawer}>
                        <div style={styles.drawerHeading}>
                            <strong>Merhaba, {username}</strong>
                            <button type="button" aria-label="Menüyü kapat" onClick={() => setOpen(false)} style={styles.closeButton}>×</button>
                        </div>
                        <Link to="/home" onClick={() => setOpen(false)} style={styles.link}>Ana sayfa</Link>
                        <Link to="/profile" onClick={() => setOpen(false)} style={styles.link}>Profilim</Link>
                        <Link to="/favorites" onClick={() => setOpen(false)} style={styles.link}>Favorilerim</Link>
                        <Link to="/orders" onClick={() => setOpen(false)} style={styles.link}>Siparişlerim</Link>
                        <Link to="/cart" onClick={() => setOpen(false)} style={styles.link}>Sepetim ({count})</Link>
                        {isAdmin && <Link to="/admin" onClick={() => setOpen(false)} style={styles.link}>Yönetim paneli</Link>}
                        <button type="button" onClick={logout} style={styles.logout}>Çıkış yap</button>
                    </aside>
                </div>
            )}
        </>
    );
}

const styles: Record<string, CSSProperties> = {
    header: { display: "flex", alignItems: "center", justifyContent: "space-between", gap: 16, padding: "12px 0", borderBottom: "1px solid #ddd" },
    brand: { color: "#222", fontSize: 20, fontWeight: 700, textDecoration: "none", whiteSpace: "nowrap" },
    menuButton: { width: 42, height: 42, border: "1px solid #bbb", borderRadius: 8, background: "#fff", cursor: "pointer", fontSize: 21 },
    backdrop: { position: "fixed", zIndex: 1000, inset: 0, background: "#0008" },
    drawer: { position: "absolute", top: 0, right: 0, display: "flex", flexDirection: "column", gap: 8, width: "min(340px, 88vw)", height: "100%", padding: 24, boxSizing: "border-box", background: "#fff", boxShadow: "-8px 0 30px #0002" },
    drawerHeading: { display: "flex", alignItems: "center", justifyContent: "space-between", paddingBottom: 16, borderBottom: "1px solid #ddd" },
    closeButton: { border: 0, background: "transparent", cursor: "pointer", fontSize: 26 },
    link: { padding: "12px 8px", color: "#222", textDecoration: "none", borderBottom: "1px solid #eee" },
    logout: { marginTop: "auto", padding: 12, border: "1px solid #ddd", borderRadius: 8, background: "#fff", cursor: "pointer", textAlign: "left" },
    error: { padding: 10, color: "#9b1c1c", background: "#fdecec", borderRadius: 6 },
};
