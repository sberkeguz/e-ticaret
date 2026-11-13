import { clearSessionStorage } from "./favorites";
import type { ApiError } from "./types/api";
import { getAccessToken, isLoggedIn, refreshSession, setAccessToken } from "./session";

const API_URL = "";

function send(path: string, options: RequestInit): Promise<Response> {
    const token = getAccessToken();
    return fetch(API_URL + path, {
        ...options,
        headers: {
            "Content-Type": "application/json",
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
            ...options.headers,
        },
    });
}

/** Backend'e istek: erişim token'ını ekler, süresi dolmuşsa bir kez yenileyip tekrar dener. */
export async function apiFetch(path: string, options: RequestInit = {}): Promise<Response> {
    // Sayfa yeni açıldıysa bellekte token yoktur: önce yenileme çereziyle alınır.
    if (!getAccessToken() && isLoggedIn()) await refreshSession();

    let res = await send(path, options);
    // Erişim token'ının süresi dolmuş olabilir: bir kez yenileyip tekrar dene.
    if (res.status === 401 && isLoggedIn() && await refreshSession()) {
        res = await send(path, options);
    }

    if (res.status === 401 || res.status === 403) {
        const body: Partial<ApiError> | null = await res.clone().json().catch(() => null);

        if (!body?.code) {
            setAccessToken(null);
            clearSessionStorage();
            window.location.href = "/login";
            throw new Error("Oturum geçersiz");
        }
    }

    return res;
}
