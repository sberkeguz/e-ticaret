import { apiFetch } from "./api";
import { isLoggedIn } from "./session";
import type { ApiError, CartItemResponse, ProductResponse } from "./types/api";

/** Tarayıcıdaki sepet satırı (girişsiz kullanıcıda sepet yalnızca burada durur). */
export interface CartItem {
    id: number;
    name: string;
    price: number;
    imageUrl: string | null;
    stock: number;
    quantity: number;
}

/** "cart-adjusted" olayının içeriği: kullanıcıya gösterilecek uyarılar. */
export interface CartAdjustedDetail {
    messages: string[];
}

/** Sunucu sepeti eski hâline döndürdüyse (ödeme sürerken değişiklik) restored = true. */
type CartSyncError = Error & { restored?: boolean };

const KEY = "cart";
const SYNCED_USER_KEY = "cart-synced-user";
const REMOVED_KEY = "cart-removed";
let syncQueue: Promise<void> = Promise.resolve();
let syncFailure: { username: string | null; error: CartSyncError } | null = null;

function readList(key: string): CartItem[] {
    try {
        const value: unknown = JSON.parse(localStorage.getItem(key) ?? "null");
        return Array.isArray(value) ? (value as CartItem[]) : [];
    } catch {
        return [];
    }
}

export const getCart = (): CartItem[] => readList(KEY);

const save = (items: CartItem[]) => {
    localStorage.setItem(KEY, JSON.stringify(items));
    window.dispatchEvent(new Event("cart-changed"));
    if (isLoggedIn()) {
        syncQueue = syncQueue.then(() => persistCart()).then(() => { syncFailure = null; }).catch((error: CartSyncError) => {
            syncFailure = error.restored ? null : { username: localStorage.getItem("username"), error };
            window.dispatchEvent(new CustomEvent("cart-sync-error", {
                detail: { message: error.message || "Sepet sunucuyla eşitlenemedi." },
            }));
        });
    }
};

const persistCart = async () => {
    const username = localStorage.getItem("username");
    const response = await apiFetch("/api/cart", {
        method: "PUT",
        body: JSON.stringify({
            items: getCart().map((item) => ({ productId: item.id, quantity: item.quantity })),
        }),
    });
    if (!response.ok) {
        const body: Partial<ApiError> | null = await response.json().catch(() => null);
        const error: CartSyncError = new Error(body?.message || "Sepet sunucuyla eşitlenemedi.");
        if (body?.code === "PAYMENT_IN_PROGRESS" && username === localStorage.getItem("username")) {
            const current = await apiFetch("/api/cart");
            if (!current.ok) throw new Error(`${error.message} Sepet yeniden yüklenemedi.`);
            const items = fromServer(await current.json());
            if (username === localStorage.getItem("username")) {
                localStorage.setItem(KEY, JSON.stringify(items));
                window.dispatchEvent(new Event("cart-changed"));
                error.restored = true;
            }
        }
        throw error;
    }
};

const fromServer = (items: CartItemResponse[]): CartItem[] => items.map((item) => ({
    id: item.productId,
    name: item.name,
    price: item.price,
    imageUrl: item.imageUrl,
    stock: item.stock,
    quantity: item.quantity,
}));

export const syncCart = async (): Promise<void> => {
    const username = localStorage.getItem("username");
    if (!isLoggedIn() || !username) return;
    await syncQueue;
    if (syncFailure?.username === username) throw syncFailure.error;
    const response = await apiFetch("/api/cart");
    if (!response.ok) {
        const body: Partial<ApiError> | null = await response.json().catch(() => null);
        throw new Error(body?.message || "Sunucu sepeti yüklenemedi.");
    }

    const serverItems = fromServer(await response.json());
    const localItems = getCart();
    const merged = new Map<number, CartItem>(serverItems.map((item) => [item.id, item]));
    if (localStorage.getItem(SYNCED_USER_KEY) !== username) {
        for (const localItem of localItems) {
            const current = merged.get(localItem.id);
            const quantity = Math.min(99, (current?.quantity ?? 0) + localItem.quantity);
            merged.set(localItem.id, {
                ...current,
                ...localItem,
                quantity,
            });
        }
    }

    let items = [...merged.values()];
    if (localStorage.getItem(SYNCED_USER_KEY) !== username && localItems.length > 0) {
        const saveResponse = await apiFetch("/api/cart", {
            method: "PUT",
            body: JSON.stringify({
                items: items.map((item) => ({ productId: item.id, quantity: item.quantity })),
            }),
        });
        if (!saveResponse.ok) {
            const body: Partial<ApiError> | null = await saveResponse.json().catch(() => null);
            throw new Error(body?.message || "Sepet sunucuyla eşitlenemedi.");
        }
        items = fromServer(await saveResponse.json());
    }

    const alreadySynced = localStorage.getItem(SYNCED_USER_KEY) === username;
    const notices = alreadySynced ? await explainServerChanges(localItems, items) : [];

    localStorage.setItem(SYNCED_USER_KEY, username);
    localStorage.setItem(KEY, JSON.stringify(items));
    window.dispatchEvent(new Event("cart-changed"));
    if (notices.length) {
        window.dispatchEvent(new CustomEvent<CartAdjustedDetail>("cart-adjusted", { detail: { messages: notices } }));
    }
};

/**
 * Sunucu sepeti, biri ürünü satın alıp stok düşünce kendisi küçültür. Burada yerel sepetle
 * karşılaştırıp kullanıcıya ne olduğunu söyleriz. Tükenen ürün "Tükendi" olarak
 * çıkarılanlar listesine eklenir. Başka sekmede satın alınan ürünler (stoğu duruyor) için uyarı yok.
 */
const explainServerChanges = async (before: CartItem[], after: CartItem[]): Promise<string[]> => {
    const notices: string[] = [];
    const afterById = new Map(after.map((item) => [item.id, item]));
    const soldOut: CartItem[] = [];
    for (const item of before) {
        const now = afterById.get(item.id);
        if (now && now.quantity < item.quantity) {
            notices.push(`${item.name}: stokta ${now.quantity} adet kaldı, sepetiniz güncellendi.`);
        } else if (!now) {
            const response = await apiFetch(`/api/products/${encodeURIComponent(item.id)}`).catch(() => null);
            const product: ProductResponse | null = response?.ok ? await response.json().catch(() => null) : null;
            const stock = Number(product?.stock ?? 0);
            if (!product || stock <= 0) {
                soldOut.push({ ...item, stock: 0, quantity: 1 });
                notices.push(`${item.name} tükendi ve sepetinizden çıkarıldı.`);
            }
        }
    }
    if (soldOut.length) {
        const soldOutIds = new Set(soldOut.map((item) => item.id));
        saveRemoved([...soldOut, ...getRemoved().filter((item) => !soldOutIds.has(item.id))]);
    }
    return notices;
};

/** Başka sekme sepeti değiştirdiyse bu sekmenin ekranını da yenile. */
window.addEventListener("storage", (event) => {
    if (event.key === KEY || event.key === REMOVED_KEY) {
        window.dispatchEvent(new Event("cart-changed"));
    }
});

/** Sepetten çıkarılan (veya tükenen) son ürünler: "geri ekle" listesi. */
export const getRemoved = (): CartItem[] => readList(REMOVED_KEY);

const saveRemoved = (items: CartItem[]) => localStorage.setItem(REMOVED_KEY, JSON.stringify(items.slice(0, 20)));

/** Sepete eklenebilecek ürün (ürün listesinden veya "geri ekle" listesinden). */
export type CartProduct = Pick<CartItem, "id" | "name" | "price" | "imageUrl"> & { stock?: number | null };

export const addToCart = (product: CartProduct): boolean => {
    const stock = Number(product.stock ?? 0);
    // Stok 0 "tükendi" demektir; tükenen ürün sepete eklenmez.
    if (stock <= 0) return false;
    saveRemoved(getRemoved().filter((item) => item.id !== product.id));
    const items = getCart();
    const existing = items.find((item) => item.id === product.id);
    if (existing) {
        if (existing.quantity < stock) existing.quantity = Math.min(existing.quantity + 1, 99);
        existing.stock = stock;
    } else {
        items.push({
            id: product.id, name: product.name, price: product.price,
            imageUrl: product.imageUrl, stock, quantity: 1,
        });
    }
    save(items);
    return true;
};

export const setQuantity = (id: number, quantity: number): void => {
    save(getCart().map((item) => item.id === id
        ? { ...item, quantity: Math.max(1, Math.min(quantity, 99, Math.max(Number(item.stock ?? 0), item.quantity))) }
        : item));
};

export const removeFromCart = (id: number): void => {
    const items = getCart();
    const removed = items.find((item) => item.id === id);
    if (removed) saveRemoved([{ ...removed, quantity: 1 }, ...getRemoved().filter((item) => item.id !== id)]);
    const left = items.filter((item) => item.id !== id);
    if (left.length === 0) saveRemoved([]);
    save(left);
};

export const clearRemoved = (): void => saveRemoved([]);



export const cartCount = (): number => getCart().reduce((sum, item) => sum + item.quantity, 0);
