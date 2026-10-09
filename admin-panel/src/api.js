const API_URL = "http://localhost:8080";

export async function apiFetch(path, options = {}) {
    const token = localStorage.getItem("token");

    const res = await fetch(API_URL + path, {
        ...options,
        headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${token}`,
            ...options.headers,
        },
    });

    if (res.status === 401 || res.status === 403) {
        // clone(): gövdeyi burada okuyoruz ama çağıran kod da okuyabilsin diye kopya üzerinden
        const body = await res.clone().json().catch(() => null);

        // "code" varsa bu bizim iş hatamız (ör. şifre kısa, yetki yok):
        // oturumu kapatma, hatayı çağıran sayfa göstersin.
        // "code" yoksa Spring Security reddetmiştir: token geçersiz veya süresi dolmuş.
        if (!body?.code) {
            localStorage.clear();
            window.location.href = "/login";
            throw new Error("Oturum geçersiz");
        }
    }

    return res;
}