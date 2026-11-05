import { apiFetch } from "./api";

const KEY = "cart";
let syncQueue = Promise.resolve();

export const getCart = () => {
    try {
        return JSON.parse(localStorage.getItem(KEY)) ?? [];
    } catch {
        return [];
    }
};

const save = (items) => {
    localStorage.setItem(KEY, JSON.stringify(items));
    window.dispatchEvent(new Event("cart-changed"));
    if (localStorage.getItem("token")) {
        syncQueue = syncQueue.then(() => persistCart()).catch((error) => {
            window.dispatchEvent(new CustomEvent("cart-sync-error", {
                detail: { message: error.message || "Sepet sunucuyla eşitlenemedi." },
            }));
        });
    }
};

const persistCart = async () => {
    const response = await apiFetch("/api/cart", {
        method: "PUT",
        body: JSON.stringify({
            items: getCart().map((item) => ({ productId: item.id, quantity: item.quantity })),
        }),
    });
    if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.message || "Sepet sunucuyla eşitlenemedi.");
    }
};

const fromServer = (items) => items.map((item) => ({
    id: item.productId,
    name: item.name,
    price: item.price,
    imageUrl: item.imageUrl,
    stock: item.stock,
    quantity: item.quantity,
}));

export const syncCart = async () => {
    if (!localStorage.getItem("token")) return;
    const response = await apiFetch("/api/cart");
    if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.message || "Sunucu sepeti yüklenemedi.");
    }

    const serverItems = fromServer(await response.json());
    const localItems = getCart();
    const merged = new Map(serverItems.map((item) => [item.id, item]));
    for (const localItem of localItems) {
        const current = merged.get(localItem.id);
        const quantity = (current?.quantity ?? 0) + localItem.quantity;
        merged.set(localItem.id, {
            ...current,
            ...localItem,
            quantity: Math.min(quantity, Number(localItem.stock) || quantity),
        });
    }

    const items = [...merged.values()];
    if (localItems.length > 0) {
        const saveResponse = await apiFetch("/api/cart", {
            method: "PUT",
            body: JSON.stringify({
                items: items.map((item) => ({ productId: item.id, quantity: item.quantity })),
            }),
        });
        if (!saveResponse.ok) {
            const body = await saveResponse.json().catch(() => null);
            throw new Error(body?.message || "Sepet sunucuyla eşitlenemedi.");
        }
    }

    localStorage.setItem(KEY, JSON.stringify(items));
    window.dispatchEvent(new Event("cart-changed"));
};

const REMOVED_KEY = "cart-removed";

export const getRemoved = () => {
    try {
        return JSON.parse(localStorage.getItem(REMOVED_KEY)) ?? [];
    } catch {
        return [];
    }
};

const saveRemoved = (items) => localStorage.setItem(REMOVED_KEY, JSON.stringify(items.slice(0, 20)));

export const addToCart = (product) => {
    saveRemoved(getRemoved().filter((item) => item.id !== product.id));
    const items = getCart();
    const stock = Number(product.stock ?? 0);
    const existing = items.find((item) => item.id === product.id);
    if (existing) {
        existing.quantity = Math.min(existing.quantity + 1, stock || existing.quantity + 1);
        existing.stock = stock;
    } else {
        items.push({
            id: product.id, name: product.name, price: product.price,
            imageUrl: product.imageUrl, stock, quantity: 1,
        });
    }
    save(items);
};

export const setQuantity = (id, quantity) => {
    save(getCart().map((item) => item.id === id
        ? { ...item, quantity: Math.max(1, Math.min(quantity, item.stock || quantity)) }
        : item));
};

export const removeFromCart = (id) => {
    const items = getCart();
    const removed = items.find((item) => item.id === id);
    if (removed) saveRemoved([{ ...removed, quantity: 1 }, ...getRemoved().filter((item) => item.id !== id)]);
    const left = items.filter((item) => item.id !== id);
    if (left.length === 0) saveRemoved([]);
    save(left);
};

export const clearRemoved = () => saveRemoved([]);



export const cartCount = () => getCart().reduce((sum, item) => sum + item.quantity, 0);
