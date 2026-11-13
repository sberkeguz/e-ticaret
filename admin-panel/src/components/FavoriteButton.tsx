import { useState, type MouseEvent } from "react";
import { toggleFavorite } from "../favorites";
import useFavorites from "../useFavorites";

interface FavoriteButtonProps {
    product: { id: number; name?: string };
    showLabel?: boolean;
}

export default function FavoriteButton({ product, showLabel = false }: FavoriteButtonProps) {
    const { ids, error } = useFavorites();
    const [saveError, setSaveError] = useState("");
    const selected = ids.includes(String(product.id));

    const toggle = (event: MouseEvent<HTMLButtonElement>) => {
        event.stopPropagation();
        try {
            toggleFavorite(product.id);
            setSaveError("");
        } catch {
            setSaveError("Favori kaydedilemedi. Tarayıcı depolama ayarlarınızı kontrol edin.");
        }
    };

    return (
        <div className={`favorite-control${showLabel ? " cart-favorite" : ""}`}>
            <button type="button" className={`${showLabel ? "button button-quiet button-small" : "favorite-button"}${selected ? " selected" : ""}`}
                aria-label={`${product.name}: ${selected ? "favorilerden çıkar" : "favorilere ekle"}`}
                aria-pressed={selected} onClick={toggle}>
                <span aria-hidden="true">{selected ? "★" : "☆"}</span>{showLabel && (selected ? " Favorilerden çıkar" : " Favorilerime ekle")}
            </button>
            {(error || saveError) && <span className="favorite-error" role="alert">{saveError || error}</span>}
        </div>
    );
}
