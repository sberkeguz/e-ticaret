const API_URL = "";

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
        const body = await res.clone().json().catch(() => null);

        if (!body?.code) {
            localStorage.clear();
            window.location.href = "/login";
            throw new Error("Oturum geçersiz");
        }
    }

    return res;
}