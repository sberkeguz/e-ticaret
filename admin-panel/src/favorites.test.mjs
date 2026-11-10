import { beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { clearSessionStorage, getFavorites, moveFavorites, toggleFavorite } from "./favorites.js";

class Storage {
    data = new Map();
    get length() { return this.data.size; }
    key(index) { return [...this.data.keys()][index] ?? null; }
    getItem(key) { return this.data.get(key) ?? null; }
    setItem(key, value) { this.data.set(key, String(value)); }
    removeItem(key) { this.data.delete(key); }
}

beforeEach(() => {
    globalThis.localStorage = new Storage();
    globalThis.window = new EventTarget();
});

test("favorites persist across logout and are isolated by username", () => {
    localStorage.setItem("username", "first");
    localStorage.setItem("token", "token");
    toggleFavorite(5);
    clearSessionStorage();
    assert.equal(localStorage.getItem("token"), null);
    localStorage.setItem("username", "second");
    assert.deepEqual(getFavorites(), []);
    toggleFavorite(8);
    localStorage.setItem("username", "first");
    assert.deepEqual(getFavorites(), ["5"]);
    toggleFavorite(5);
    assert.deepEqual(getFavorites(), []);
});

test("favorites move when the username changes", () => {
    localStorage.setItem("username", "old");
    toggleFavorite(9);
    moveFavorites("old", "new");
    localStorage.setItem("username", "new");
    assert.deepEqual(getFavorites(), ["9"]);
    localStorage.setItem("username", "old");
    assert.deepEqual(getFavorites(), []);
});

test("invalid saved data and missing identities are reported", () => {
    assert.throws(() => getFavorites());
    localStorage.setItem("username", "first");
    localStorage.setItem("favorites:first", "invalid");
    assert.throws(() => getFavorites());
});
