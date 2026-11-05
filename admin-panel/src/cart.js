const KEY = "cart";

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
