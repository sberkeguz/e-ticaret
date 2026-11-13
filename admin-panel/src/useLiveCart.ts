import { useEffect, useState } from "react";
import { syncCart, type CartAdjustedDetail } from "./cart";

const REFRESH_MS = 10_000;

/**
 * Sepet veya ödeme sayfası açıkken sepeti sunucuyla düzenli eşitler: başka biri ürünü
 * satın alıp stok bittiyse ürün birkaç saniye içinde sepetten çıkar.
 * Sekmeye geri dönüldüğünde hemen eşitler. Dönen değer: kullanıcıya gösterilecek uyarı ve onu silen fonksiyon.
 */
export default function useLiveCart(enabled = true): [string, () => void] {
    const [notice, setNotice] = useState("");

    useEffect(() => {
        if (!enabled) return undefined;
        const refresh = () => { syncCart().catch(() => {}); };
        const onVisible = () => { if (document.visibilityState === "visible") refresh(); };
        const onAdjusted = (event: Event) => setNotice((event as CustomEvent<CartAdjustedDetail>).detail.messages.join(" "));

        const timer = setInterval(refresh, REFRESH_MS);
        window.addEventListener("focus", refresh);
        document.addEventListener("visibilitychange", onVisible);
        window.addEventListener("cart-adjusted", onAdjusted);
        return () => {
            clearInterval(timer);
            window.removeEventListener("focus", refresh);
            document.removeEventListener("visibilitychange", onVisible);
            window.removeEventListener("cart-adjusted", onAdjusted);
        };
    }, [enabled]);

    return [notice, () => setNotice("")];
}
