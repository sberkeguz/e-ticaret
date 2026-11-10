import { useEffect, useState } from "react";
import { getFavorites } from "./favorites";

export default function useFavorites() {
    const [state, setState] = useState({ ids: [], error: "" });

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
