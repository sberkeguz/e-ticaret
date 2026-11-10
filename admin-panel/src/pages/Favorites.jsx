import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch } from "../api";
import StoreHeader from "../components/StoreHeader";
import StoreProduct from "../components/StoreProduct";
import useFavorites from "../useFavorites";

export default function Favorites() {
    const { ids, error: favoritesError } = useFavorites();
    const [products, setProducts] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");

    useEffect(() => {
        let active = true;
        apiFetch("/api/products")
            .then(async (response) => {
                if (!response.ok) throw new Error("Ürünler yüklenemedi.");
                const data = await response.json();
                if (!Array.isArray(data)) throw new Error("Ürün listesi okunamadı.");
                if (active) setProducts(data);
            })
            .catch((loadError) => { if (active) setError(loadError.message || "Sunucuya ulaşılamadı."); })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, []);

    const favorites = products.filter((product) => ids.includes(String(product.id)));
    return (
        <div className="storefront">
            <StoreHeader />
            <main className="store-content">
                <div className="store-main-head">
                    <div><h1>Favorilerim</h1><p>Beğendiğiniz ürünler bu tarayıcıda saklanır.</p></div>
                </div>
                {(error || favoritesError) && <p className="error-banner" role="alert">{error || favoritesError}</p>}
                {loading ? <div className="loading-state">Favoriler yükleniyor…</div> : !error && !favoritesError && (
                    favorites.length ? (
                        <div className="store-grid">{favorites.map((product) => <StoreProduct key={product.id} product={product} />)}</div>
                    ) : (
                        <div className="surface empty-state">
                            <div className="empty-icon">☆</div>
                            <h3>Henüz favori ürününüz yok</h3>
                            <p>Beğendiğiniz ürünlerdeki yıldıza dokunarak onları buraya ekleyebilirsiniz.</p>
                            <Link className="button button-quiet" to="/home">Ürünleri keşfet</Link>
                        </div>
                    )
                )}
            </main>
        </div>
    );
}
