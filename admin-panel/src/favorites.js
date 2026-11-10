const PREFIX = "favorites:";

const keyFor = (username) => `${PREFIX}${encodeURIComponent(username)}`;

export function getFavorites() {
    const username = localStorage.getItem("username");
    if (!username) throw new Error("Favoriler için oturum bilgisi bulunamadı.");
    const value = localStorage.getItem(keyFor(username));
    if (value === null) return [];
    const ids = JSON.parse(value);
    if (!Array.isArray(ids) || ids.some((id) => typeof id !== "string")) {
        throw new Error("Favori listesi okunamadı.");
    }
    return ids;
}

export function toggleFavorite(id) {
    const username = localStorage.getItem("username");
    if (!username) throw new Error("Favoriler için oturum bilgisi bulunamadı.");
    const productId = String(id);
    const ids = getFavorites();
    const next = ids.includes(productId) ? ids.filter((item) => item !== productId) : [...ids, productId];
    localStorage.setItem(keyFor(username), JSON.stringify(next));
    window.dispatchEvent(new Event("favorites-changed"));
}

export function moveFavorites(oldUsername, newUsername) {
    if (!oldUsername || oldUsername === newUsername) return;
    const value = localStorage.getItem(keyFor(oldUsername));
    if (value === null) localStorage.removeItem(keyFor(newUsername));
    else localStorage.setItem(keyFor(newUsername), value);
    localStorage.removeItem(keyFor(oldUsername));
    window.dispatchEvent(new Event("favorites-changed"));
}

export function clearSessionStorage() {
    for (let index = localStorage.length - 1; index >= 0; index -= 1) {
        const key = localStorage.key(index);
        if (key && !key.startsWith(PREFIX)) localStorage.removeItem(key);
    }
}
