import { useEffect, useState } from "react";
import { getFavorites } from "./favorites";

interface FavoritesState {
    ids: string[];
    error: string;
}

export default function useFavorites(): FavoritesState {
    const [state, setState] = useState<FavoritesState>({ ids: [], error: "" });

    useEffect(() => {
        const update = () => {
            try {
                setState({ ids: getFavorites(), error: "" });
            } catch {
                setState({ ids: [], error: "Favoriler okunamadı. Tarayıcı depolama ayarlarınızı kontrol edin." });
            }
        };
        update();
        window.addEventListener("favorites-changed", update);
        window.addEventListener("storage", update);
        return () => {
            window.removeEventListener("favorites-changed", update);
            window.removeEventListener("storage", update);
        };
    }, []);

    return state;
}
