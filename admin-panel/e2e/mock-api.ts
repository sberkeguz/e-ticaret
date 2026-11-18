import type { Page, Route } from "@playwright/test";
import type {
    CartItemResponse, OrderResponse, PaymentResult, ProductResponse, ProfileResponse, Role,
} from "../src/types/api";

/**
 * Testler için sahte backend. Gerçek API'nin cevap biçimlerini (src/types/api.ts) kullanır ve
 * durumu bellekte tutar: sepet, siparişler, ödeme sonucu, "2D ödeme alınabilir mi".
 * Tanınmayan istek 404 döner ve `unknown` listesine yazılır (test sonunda boş olmalı).
 */
export interface MockState {
    username: string;
    role: Role;
    products: ProductResponse[];
    cart: { productId: number; quantity: number }[];
    orders: OrderResponse[];
    profile: ProfileResponse;
    /** GET /api/payments/availability cevabı (RabbitMQ çalışıyor mu). */
    paymentsAvailable: boolean;
    /** Sıradaki ödeme durum sorularının cevapları; boşsa "processing". */
    statusAnswers: { result: PaymentResult; message?: string | null }[];
    /** Backend'e gelen ödeme istekleri (gövdeleriyle). */
    payments: { path: string; body: Record<string, unknown> }[];
    cancelled: number[];
    unknown: string[];
}

export const product = (id: number, name: string, price: number, stock = 10): ProductResponse => ({
    id, name, price, stock, description: `${name} açıklaması`, imageUrl: null, origin: null,
    brandId: null, brandName: null, categoryIds: [], categoryNames: [], testProduct: false,
});

export const order = (id: number, overrides: Partial<OrderResponse> = {}): OrderResponse => ({
    id, username: "musteri", firstName: "Ayşe", lastName: "Yılmaz", phone: "0555 123 45 67",
    city: "İstanbul", district: "Kadıköy", neighborhood: "Moda", address: "Test sok. 1",
    total: 20, status: "PAYMENT_PENDING", createdAt: "2026-11-18T10:00:00Z",
    items: [{ productId: 1, productName: "Elma", price: 10, quantity: 2 }],
    paymentProcessing: false,
    ...overrides,
});

export function newState(overrides: Partial<MockState> = {}): MockState {
    return {
        username: "musteri",
        role: "USER",
        products: [product(1, "Elma", 10), product(2, "Armut", 15)],
        cart: [],
        orders: [],
        profile: {
            username: "musteri", role: "USER", firstName: "Ayşe", lastName: "Yılmaz", phone: "0555 123 45 67",
            email: "ayse@example.com", identityNumber: "11111111110", city: "İstanbul", district: "Kadıköy",
            neighborhood: "Moda", address: "Test sok. 1",
        },
        paymentsAvailable: true,
        statusAnswers: [],
        payments: [],
        cancelled: [],
        unknown: [],
        ...overrides,
    };
}

const json = (route: Route, body: unknown, status = 200, headers: Record<string, string> = {}) =>
    route.fulfill({ status, contentType: "application/json", headers, body: JSON.stringify(body) });

const cartResponse = (state: MockState): CartItemResponse[] => state.cart.flatMap((line) => {
    const item = state.products.find((candidate) => candidate.id === line.productId);
    return item ? [{ productId: item.id, name: item.name, price: item.price, imageUrl: null, stock: item.stock, quantity: line.quantity }] : [];
});

/** Oturum açık başlar (yenileme çereziyle token alınır) ve tüm /api istekleri sahte backend'e gider. */
export async function mockBackend(page: Page, state: MockState): Promise<MockState> {
    await page.addInitScript(({ username, role }) => {
        if (!sessionStorage.getItem("e2e-started")) {
            sessionStorage.setItem("e2e-started", "1");
            localStorage.clear();
            localStorage.setItem("username", username);
            localStorage.setItem("role", role);
        }
    }, { username: state.username, role: state.role });

    // Türkiye il/ilçe/mahalle listesi (dış servis).
    await page.route("https://api.turkiyeapi.dev/**", (route) => {
        const url = route.request().url();
        if (url.includes("/provinces")) return json(route, { data: [{ id: 34, name: "İstanbul" }, { id: 6, name: "Ankara" }] });
        if (url.includes("/districts")) return json(route, { data: [{ id: 1, name: "Kadıköy" }] });
        return json(route, { data: [{ id: 1, name: "Moda" }] });
    });

    await page.route((url) => url.pathname.startsWith("/api/"), async (route) => {
        const request = route.request();
        const url = new URL(request.url());
        const path = url.pathname;
        const method = request.method();
        const body = request.postData() ? JSON.parse(request.postData() as string) : {};
        let match: RegExpMatchArray | null;

        if (path === "/api/auth/refresh" || path === "/api/auth/login") {
            return json(route, { status: "ok", role: state.role, message: "", username: state.username }, 200,
                { Authorization: "Bearer test-token" });
        }
        if (path === "/api/auth/logout") return route.fulfill({ status: 204 });
        if (path === "/api/cart") {
            if (method === "PUT") state.cart = body.items;
            return json(route, cartResponse(state));
        }
        if (path === "/api/user/categories" || path === "/api/user/brands") return json(route, []);
        if (path === "/api/products/search") {
            return json(route, { items: state.products, page: 0, size: 24, totalItems: state.products.length, totalPages: 1 });
        }
        if (path === "/api/products") return json(route, state.products);
        if ((match = path.match(/^\/api\/products\/(\d+)$/))) {
            const found = state.products.find((item) => item.id === Number(match![1]));
            return found ? json(route, found) : json(route, { status: 404, code: "NOT_FOUND", message: "Ürün yok" }, 404);
        }
        if (path === "/api/profile") return json(route, state.profile);
        if (path === "/api/orders") return json(route, state.orders);
        if (path === "/api/payments/availability") {
            return json(route, { available: state.paymentsAvailable, unavailableSince: state.paymentsAvailable ? null : "2026-11-18T10:00:00Z" });
        }
        if (path === "/api/payments/checkout" || /^\/api\/payments\/orders\/\d+\/pay$/.test(path)) {
            state.payments.push({ path, body });
            const existing = path.match(/orders\/(\d+)\/pay/);
            const orderId = existing ? Number(existing[1]) : 100 + state.orders.length;
            if (!existing) {
                state.orders.unshift(order(orderId, { paymentProcessing: true }));
                state.cart = [];
            }
            return json(route, { result: "processing", orderId, threeDSHtmlContent: null, checkoutFormContent: null });
        }
        if ((match = path.match(/^\/api\/payments\/orders\/(\d+)\/status$/))) {
            const answer = state.statusAnswers.shift() ?? { result: "processing" as PaymentResult };
            return json(route, { result: answer.result, message: answer.message ?? null });
        }
        if ((match = path.match(/^\/api\/payments\/orders\/(\d+)\/cancel$/))) {
            const id = Number(match[1]);
            state.cancelled.push(id);
            state.orders = state.orders.map((item) => (item.id === id ? { ...item, status: "CANCELLED" } : item));
            return route.fulfill({ status: 204 });
        }

        state.unknown.push(`${method} ${path}`);
        return json(route, { status: 404, code: "NOT_FOUND", message: "Testte tanımsız istek" }, 404);
    });
    return state;
}
