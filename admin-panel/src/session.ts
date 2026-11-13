// Oturum. Erişim token'ı (15 dk) yalnızca bu sekmenin belleğinde durur; localStorage'a yazılmaz,
// böylece olası bir XSS açığında okunup çalınamaz. Süresi dolunca ya da sayfa yenilenince yenileme
// çereziyle (HttpOnly: JavaScript göremez) sunucudan yenisi alınır.
// localStorage'da yalnızca gizli olmayan bilgiler kalır: kullanıcı adı ve rol (menüler için).

import type { AuthResponse } from "./types/api";

const CSRF_HEADER = { "X-Requested-With": "fetch" };
const RETRY_AFTER_MS = 400;

let accessToken: string | null = null;
let refreshing: Promise<boolean> | null = null;

// Eski sürümden kalan, localStorage'daki token artık kullanılmaz.
try { localStorage.removeItem("token"); } catch { /* gizli sekme vb. */ }

export const getAccessToken = (): string | null => accessToken;

export function setAccessToken(token: string | null | undefined): void {
    accessToken = token || null;
}

/** Giriş yapılmış görünüyor mu (gerçek doğrulamayı sunucu yapar). */
export function isLoggedIn(): boolean {
    return Boolean(localStorage.getItem("username"));
}

export function tokenFrom(response: Response): string | null {
    const header = response.headers.get("Authorization");
    return header?.startsWith("Bearer ") ? header.slice(7) : null;
}

async function tryRefresh(): Promise<boolean> {
    const response = await fetch("/api/auth/refresh", { method: "POST", headers: CSRF_HEADER });
    if (!response.ok) return false;
    accessToken = tokenFrom(response);
    const body: Partial<AuthResponse> | null = await response.json().catch(() => null);
    if (body?.role) localStorage.setItem("role", body.role);
    if (body?.username) localStorage.setItem("username", body.username);
    return Boolean(accessToken);
}

/**
 * Yenileme çereziyle yeni erişim token'ı alır. Aynı anda gelen çağrılar tek isteği paylaşır.
 * İlk deneme reddedilirse kısa süre sonra bir kez daha denenir: başka bir sekme aynı anda
 * yenilediyse tarayıcıya o arada yeni çerez gelmiştir.
 */
export function refreshSession(): Promise<boolean> {
    if (!refreshing) {
        refreshing = (async () => {
            try {
                if (await tryRefresh()) return true;
                await new Promise((resolve) => setTimeout(resolve, RETRY_AFTER_MS));
                return await tryRefresh();
            } catch {
                return false;
            } finally {
                refreshing = null;
            }
        })();
    }
    return refreshing;
}

/** Çıkış: oturum sunucuda da kapanır, çerez silinir. */
export async function logoutSession(): Promise<void> {
    accessToken = null;
    try {
        await fetch("/api/auth/logout", { method: "POST", headers: CSRF_HEADER });
    } catch { /* bağlantı yoksa da yerel oturum kapanır */ }
}
