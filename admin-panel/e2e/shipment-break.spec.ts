import { expect, test, type Page } from "@playwright/test";
import type { AdminOrderResponse, AdminShipment, CustomerShipment, OrderResponse, ShipmentStatus } from "../src/types/api";
import { adminOrder, mockBackend, newState, order, trackConsoleErrors, type MockAnswer, type MockState } from "./mock-api";

/**
 * Tester (QA) kırma testleri: kargo ön yüzünde kenar durumlar, sıra karışıklığı, XSS ve
 * mevcut akışların (C5) gerilememesi. Backend sahte (mock-api.ts).
 */

const adminShipment = (status: ShipmentStatus, overrides: Partial<AdminShipment> = {}): AdminShipment => ({
    status,
    carrierName: "Hızlı Kargo",
    trackingNumber: status === "REQUESTED" || status === "FAILED" ? null : "HK123456789",
    trackingUrl: null,
    statusAt: "2026-11-23T09:00:00Z",
    requestNo: 1,
    failureReason: null,
    canRetry: false,
    ...overrides,
});
const customerShipment = (overrides: Partial<CustomerShipment> = {}): CustomerShipment => ({
    status: "CREATED", carrierName: "Hızlı Kargo", trackingNumber: "HK987654321",
    trackingUrl: "https://kargo.example.com/takip?ref=abc", statusAt: "2026-11-23T09:00:00Z", ...overrides,
});
const shippable = (id: number, overrides: Partial<AdminOrderResponse> = {}) => adminOrder(id, { canShip: true, ...overrides });

const row = (page: Page, id: number) =>
    page.getByRole("row").filter({ has: page.getByRole("cell", { name: `#${id}`, exact: true }) });
const shipmentCell = (page: Page, id: number) => row(page, id).getByRole("cell").nth(7);
const statusSelect = (page: Page, id: number) => row(page, id).getByRole("combobox");
const dialog = (page: Page) => page.getByRole("dialog");
const banner = (page: Page) => page.getByRole("alert");
const shipButton = (page: Page, id: number) => shipmentCell(page, id).getByRole("button", { name: "Kargoya ver" });

function deferred<T = MockAnswer>() {
    let release: (value: T) => void = () => {};
    const gate = new Promise<T>((resolve) => { release = resolve; });
    return { gate, release };
}

async function openAdminOrders(page: Page, orders: AdminOrderResponse[], extra: Partial<MockState> = {}) {
    const errors = trackConsoleErrors(page);
    const state = await mockBackend(page, newState({ username: "admin", role: "ADMIN", adminOrders: orders, ...extra }));
    await page.goto("/admin/orders");
    await expect(row(page, orders[0]!.id)).toBeVisible();
    return { state, errors };
}

async function shipFromRow(page: Page, id: number) {
    await shipButton(page, id).click();
    await dialog(page).getByRole("button", { name: "Evet, kargoya ver" }).click();
}

/**
 * GET /api/admin/orders'a müdahale: `hold()` çağrıldıktan sonraki ilk liste isteğinin cevabı
 * istek anındaki veriyle hazırlanır ama `release()` çağrılana kadar bekletilir (geç gelen eski cevap).
 * `failNext()` sonraki ilk liste isteğini 500 ile cevaplar.
 */
async function controlListRequests(page: Page, state: MockState) {
    let held: { gate: Promise<void>; release: () => void } | null = null;
    let fail = false;
    await page.route((url) => url.pathname === "/api/admin/orders", async (route) => {
        if (route.request().method() !== "GET") return route.fallback();
        if (fail) {
            fail = false;
            state.listRequests += 1;
            return route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ status: 500, code: "INTERNAL", message: "x" }) });
        }
        if (!held) return route.fallback();
        const current = held;
        held = null;
        state.listRequests += 1;
        const snapshot = JSON.parse(JSON.stringify(state.adminOrders)) as AdminOrderResponse[];
        await current.gate;
        return route.fulfill({
            status: 200, contentType: "application/json",
            body: JSON.stringify({ items: snapshot, page: 0, size: 50, totalItems: snapshot.length, totalPages: 1 }),
        });
    });
    return {
        hold() {
            const { gate, release } = deferred<void>();
            held = { gate, release: () => release() };
            return () => release();
        },
        failNext() { fail = true; },
    };
}

// ---------------------------------------------------------------- Yönetici

test("QA-XSS admin: firma, takip no, sebep ve backend mesajındaki HTML metin olarak görünür, çalışmaz", async ({ page }) => {
    const evil = "<img src=x onerror=\"window.__xss=1\">";
    const { errors } = await openAdminOrders(page, [
        adminOrder(1, { status: "SHIPPED", shipment: adminShipment("CREATED", { carrierName: evil, trackingNumber: "<script>window.__xss=2</script>" }) }),
        adminOrder(2, { shipment: adminShipment("FAILED", { failureReason: evil, canRetry: true }) }),
        shippable(3),
    ], {
        shipmentAnswers: [{ status: 409, body: { status: 409, code: "SHIPMENT_NOT_ALLOWED", message: evil } }],
    });

    await expect(shipmentCell(page, 1)).toContainText(evil);
    await expect(shipmentCell(page, 1)).toContainText("<script>window.__xss=2</script>");
    await expect(shipmentCell(page, 2)).toContainText(evil);
    await shipFromRow(page, 3);
    await expect(banner(page)).toHaveText(`#3: ${evil}`);
    await expect(page.locator("td img, td script, [role=alert] img")).toHaveCount(0);
    expect(await page.evaluate(() => (window as unknown as { __xss?: number }).__xss)).toBeUndefined();
    expect(errors).toEqual([]);
});

test("QA-C1 admin: dizi/sayı/false/küçük harf durumlu gönderi '—' gösterir ve durum kutusunu kilitler (Developer kararı)", async ({ page }) => {
    const { state, errors } = await openAdminOrders(page, [
        shippable(1, { shipment: [] as unknown as AdminShipment }),
        shippable(2, { shipment: 42 as unknown as AdminShipment }),
        shippable(3, { shipment: false as unknown as AdminShipment }),
        shippable(4, { shipment: { ...adminShipment("CREATED"), status: "created" } as unknown as AdminShipment }),
        shippable(5, { shipment: { ...adminShipment("FAILED", { canRetry: true }), requestNo: "1" } as unknown as AdminShipment }),
    ]);

    for (const id of [1, 2, 3, 4, 5]) {
        await expect(shipmentCell(page, id)).toHaveText("—");
        await expect(statusSelect(page, id)).toBeDisabled();
    }
    await expect(page.getByRole("button", { name: /Kargoya ver|Tekrar dene/ })).toHaveCount(0);
    expect(state.unknown).toEqual([]);
    expect(errors).toEqual([]);
});

test("QA-A2: onay penceresi karartılmış alana tıklayınca kapanır, istek gitmez", async ({ page }) => {
    const { state, errors } = await openAdminOrders(page, [shippable(1)]);

    await shipButton(page, 1).click();
    await expect(dialog(page)).toContainText("#1");
    // Pencerenin dışı (dialog öğesinin kendisi = backdrop) sol üst köşe.
    await page.mouse.click(5, 5);
    await expect(dialog(page)).toBeHidden();
    await expect(shipButton(page, 1)).toBeEnabled();
    expect(state.shipmentRequests).toEqual([]);
    expect(errors).toEqual([]);
});

test("QA-A3 klavye: Tab/Enter ile tüm akış; istek sürerken tekrar Enter tek istek gönderir", async ({ page }) => {
    const { gate, release } = deferred();
    const base = shippable(1);
    const { state, errors } = await openAdminOrders(page, [base], { shipmentAnswers: [{ gate }] });

    await shipButton(page, 1).focus();
    await page.keyboard.press("Enter");
    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page).getByRole("button", { name: "Vazgeç" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Evet, kargoya ver" })).toBeFocused();
    await page.keyboard.press("Enter");
    await page.keyboard.press("Enter");
    await page.keyboard.press(" ");
    await expect(dialog(page).getByRole("button", { name: "Kargoya veriliyor…" })).toBeDisabled();

    release({ status: 202, body: { ...base, canShip: false, shipment: adminShipment("REQUESTED") } });
    await expect(shipmentCell(page, 1)).toContainText("Kargo hazırlanıyor");
    expect(state.shipmentRequests).toHaveLength(1);
    expect(errors).toEqual([]);
});

test("QA-A4/K-A7: kargoya verildikten sonra (yenilemeden) durum kutusu kilitlenir", async ({ page }) => {
    const { errors } = await openAdminOrders(page, [shippable(1)]);

    await expect(statusSelect(page, 1)).toBeEnabled();
    await shipFromRow(page, 1);
    await expect(shipmentCell(page, 1)).toContainText("Kargo hazırlanıyor");
    await expect(statusSelect(page, 1)).toBeDisabled();
    await expect(statusSelect(page, 1)).toHaveAttribute("title", "Kargo durumu kargo firmasından güncellenir.");
    expect(errors).toEqual([]);
});

test("QA-A9: Tekrar dene onay penceresi: Vazgeç istek göndermez; onayda satır REQUESTED olur, kutu kilitlenir", async ({ page }) => {
    const { state, errors } = await openAdminOrders(page, [
        adminOrder(1, { shipment: adminShipment("FAILED", { canRetry: true, requestNo: 3 }) }),
    ]);

    await shipmentCell(page, 1).getByRole("button", { name: "Tekrar dene" }).click();
    await expect(dialog(page)).toContainText("#1");
    await dialog(page).getByRole("button", { name: "Vazgeç" }).click();
    await expect(dialog(page)).toBeHidden();
    expect(state.shipmentRequests).toEqual([]);

    await shipmentCell(page, 1).getByRole("button", { name: "Tekrar dene" }).click();
    await dialog(page).getByRole("button", { name: "Evet, tekrar dene" }).click();
    await expect(shipmentCell(page, 1)).toContainText("Kargo hazırlanıyor");
    await expect(shipmentCell(page, 1).getByRole("button", { name: "Tekrar dene" })).toHaveCount(0);
    await expect(statusSelect(page, 1)).toBeDisabled();
    expect(state.shipmentRequests).toEqual([{ path: "/api/admin/orders/1/shipment/retry", body: { expectedRequestNo: 3 } }]);
    expect(errors).toEqual([]);
});

test("QA-A10: iptal edilmiş siparişte FAILED+canRetry gelse de Tekrar dene görünmez (Kargoya ver ile aynı savunma)", async ({ page }) => {
    const { errors } = await openAdminOrders(page, [
        adminOrder(1, { status: "CANCELLED", shipment: adminShipment("FAILED", { canRetry: true }) }),
    ]);

    await expect(shipmentCell(page, 1)).toContainText("Kargo oluşturulamadı");
    await expect(shipmentCell(page, 1).getByRole("button", { name: "Tekrar dene" })).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("QA-A11: zaman aşımından sonra geç gelen başarılı cevap satırı değiştirmez, konsol hatası yok", async ({ page }) => {
    await page.clock.install();
    const { gate, release } = deferred();
    const base = shippable(1);
    const { state, errors } = await openAdminOrders(page, [base], { shipmentAnswers: [{ gate }] });

    await shipFromRow(page, 1);
    await page.clock.fastForward(15_000);
    await expect(banner(page)).toContainText("#1: Sunucu zamanında yanıt vermedi.");
    release({ status: 202, body: { ...base, canShip: false, shipment: adminShipment("REQUESTED") } });
    await page.clock.fastForward(1_000);

    await expect(shipButton(page, 1)).toBeEnabled();
    await expect(shipmentCell(page, 1)).not.toContainText("Kargo hazırlanıyor");
    await expect(banner(page)).toContainText("Sunucu zamanında yanıt vermedi.");
    expect(state.shipmentRequests).toHaveLength(1);
    expect(errors).toEqual([]);
});

test("QA-A13: 409 sonrası yeniden yükleme başarısız olursa, sonraki başarılı yükleme hata bandını temizler", async ({ page }) => {
    const base = shippable(1);
    const { state, errors } = await openAdminOrders(page, [base], {
        shipmentAnswers: [{ status: 409, body: { status: 409, code: "SHIPMENT_EXISTS", message: "exists" } }],
    });
    const list = await controlListRequests(page, state);
    list.failNext();

    await shipFromRow(page, 1);
    // 409 sebebi, ardından gelen yükleme hatasının altında kaybolmaz: ikisi aynı bantta, 409 önce.
    await expect(banner(page)).toHaveText("#1: Bu sipariş zaten kargoya verildi. Siparişler yüklenemedi.");

    // Yönetici arama yapar: liste başarıyla gelir, eski "yüklenemedi" bandı kalmamalı.
    const before = state.listRequests;
    await page.getByPlaceholder("Kullanıcı, ad, telefon veya ürün ara...").fill("Ayşe");
    await expect.poll(() => state.listRequests).toBeGreaterThan(before);
    await expect(row(page, 1)).toBeVisible();
    await expect(banner(page)).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("QA-A13 sıra: 409 sonrası geç gelen eski liste, arada kargoya verilen başka satırı geri 'Kargoya ver'e döndürmez", async ({ page }) => {
    const { state, errors } = await openAdminOrders(page, [shippable(1), shippable(2)], {
        // 1. satır: başka yerde değişmiş (liste yenilenir). 2. satır: varsayılan başarı.
        shipmentAnswers: [{ status: 409, body: { status: 409, code: "SHIPMENT_NOT_ALLOWED", message: "Sipariş artık kargoya verilemez." } }],
    });
    const list = await controlListRequests(page, state);
    const releaseList = list.hold();

    await shipFromRow(page, 1);
    await expect(banner(page)).toHaveText("#1: Sipariş artık kargoya verilemez.");
    // Yeniden yükleme isteği gitti ama cevabı gecikiyor; yönetici bu arada 2. satırı kargoya verir.
    await shipFromRow(page, 2);
    await expect(shipmentCell(page, 2)).toContainText("Kargo hazırlanıyor");

    releaseList();
    await page.waitForTimeout(300);
    await expect(shipmentCell(page, 2)).toContainText("Kargo hazırlanıyor");
    await expect(shipButton(page, 2)).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("QA-A3 gezinme: istek sürerken sayfadan çıkıp dönünce aynı sipariş için ikinci istek gönderilemez, konsol hatası yok", async ({ page }) => {
    const { gate, release } = deferred();
    const base = shippable(1);
    const { state, errors } = await openAdminOrders(page, [base], { shipmentAnswers: [{ gate }] });
    const navigate = (path: string) => page.evaluate((target) => {
        window.history.pushState({}, "", target);
        window.dispatchEvent(new PopStateEvent("popstate"));
    }, path);

    await shipFromRow(page, 1);
    await expect(dialog(page).getByRole("button", { name: "Kargoya veriliyor…" })).toBeDisabled();
    await navigate("/orders");
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await navigate("/admin/orders");
    await expect(row(page, 1)).toBeVisible();

    // İlk istek hâlâ cevapsız: aynı sipariş için yeniden "Kargoya ver" kullanılabilir olmamalı.
    if (await shipButton(page, 1).isEnabled().catch(() => false)) {
        await shipFromRow(page, 1).catch(() => {});
    }
    release({ status: 202, body: { ...base, canShip: false, shipment: adminShipment("REQUESTED") } });
    await page.waitForTimeout(300);
    expect(state.shipmentRequests).toHaveLength(1);
    expect(errors).toEqual([]);
});

// ---------------------------------------------------------------- C5 mevcut akışlar (yönetici)

test("QA-C5 admin: gönderisiz siparişte iptal onayı reddedilirse istek gitmez; kabul edilirse iptal olur ve Kargoya ver kaybolur", async ({ page }) => {
    const { state, errors } = await openAdminOrders(page, [shippable(1)]);

    page.once("dialog", (prompt) => { void prompt.dismiss(); });
    await statusSelect(page, 1).selectOption("CANCELLED");
    await expect(statusSelect(page, 1)).toHaveValue("PREPARING");
    expect(state.statusPatches).toEqual([]);

    page.once("dialog", (prompt) => { void prompt.accept(); });
    await statusSelect(page, 1).selectOption("CANCELLED");
    await expect(statusSelect(page, 1)).toHaveValue("CANCELLED");
    await expect(statusSelect(page, 1)).toBeDisabled();
    await expect(shipButton(page, 1)).toHaveCount(0);
    expect(state.statusPatches).toEqual([{ id: 1, status: "CANCELLED" }]);
    expect(state.shipmentRequests).toEqual([]);
    expect(errors).toEqual([]);
});

test("QA-C5 admin: FAILED gönderili sipariş iptal edilebilir; ödeme bekleyen sipariş hazırlanıyora alınabilir", async ({ page }) => {
    const { state, errors } = await openAdminOrders(page, [
        adminOrder(1, { shipment: adminShipment("FAILED", { canRetry: true }) }),
        adminOrder(2, { status: "PAYMENT_PENDING" }),
    ]);

    page.once("dialog", (prompt) => { void prompt.accept(); });
    await statusSelect(page, 1).selectOption("CANCELLED");
    await expect(statusSelect(page, 1)).toHaveValue("CANCELLED");
    await statusSelect(page, 2).selectOption("PREPARING");
    await expect(statusSelect(page, 2)).toHaveValue("PREPARING");
    expect(state.statusPatches).toEqual([{ id: 1, status: "CANCELLED" }, { id: 2, status: "PREPARING" }]);
    expect(errors).toEqual([]);
});

// ---------------------------------------------------------------- Müşteri

const card = (page: Page) => page.getByRole("article");
const shipmentInfo = (page: Page) => page.getByRole("region", { name: "Kargo bilgisi" });

async function openMyOrders(page: Page, orders: OrderResponse[]) {
    const errors = trackConsoleErrors(page);
    const state = await mockBackend(page, newState({ orders }));
    await page.goto("/orders");
    await expect(card(page).first()).toBeVisible();
    return { state, errors };
}

test("QA-B2 URL: yalnız https bağlantı (büyük harf, boşluklu dahil) gösterilir; //, data:, JAVASCRIPT:, kullanıcı:parola@, sekmeli javascript reddedilir", async ({ page }) => {
    const urls: [string, string | null][] = [
        ["HTTPS://Kargo.Example.com/takip?no=1", "https://kargo.example.com/takip?no=1"],
        ["  https://kargo.example.com/a  ", "https://kargo.example.com/a"],
        ["//evil.example.com/x", null],
        ["data:text/html,<script>alert(1)</script>", null],
        ["JAVASCRIPT:alert(1)", null],
        ["java\tscript:alert(1)", null],
        ["https://kullanici:parola@kargo.example.com/", null],
        ["https://kullanici@kargo.example.com/", null],
        ["ftp://kargo.example.com/", null],
        ["", null],
    ];
    const { errors } = await openMyOrders(page, urls.map(([url], index) =>
        order(index + 1, { status: "SHIPPED", shipment: customerShipment({ trackingUrl: url }) })));

    await expect(shipmentInfo(page)).toHaveCount(urls.length);
    const infos = await shipmentInfo(page).all();
    for (const [index, [, expected]] of urls.entries()) {
        const link = infos[index]!.getByRole("link", { name: "Kargonuzu takip edin" });
        if (expected) await expect(link, urls[index]![0]).toHaveAttribute("href", expected);
        else await expect(link, urls[index]![0]).toHaveCount(0);
    }
    expect(errors).toEqual([]);
});

test("QA-XSS müşteri: firma ve takip numarasındaki HTML metin olarak görünür, çalışmaz", async ({ page }) => {
    const evil = "<img src=x onerror=\"window.__xss=1\">";
    const { errors } = await openMyOrders(page, [
        order(1, { status: "SHIPPED", shipment: customerShipment({ carrierName: evil, trackingNumber: "<script>window.__xss=2</script>" }) }),
    ]);

    await expect(shipmentInfo(page)).toContainText(evil);
    await expect(shipmentInfo(page)).toContainText("<script>window.__xss=2</script>");
    await expect(shipmentInfo(page).locator("img, script")).toHaveCount(0);
    expect(await page.evaluate(() => (window as unknown as { __xss?: number }).__xss)).toBeUndefined();
    expect(errors).toEqual([]);
});

test("QA-B2 uzun metin: çok uzun firma adı ve takip numarası müşteri kartını yatay taşırmaz", async ({ page }) => {
    const { errors } = await openMyOrders(page, [
        order(1, { status: "SHIPPED", shipment: customerShipment({ carrierName: "K".repeat(400), trackingNumber: "9".repeat(400) }) }),
    ]);

    await expect(shipmentInfo(page)).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    const cardBox = await card(page).first().boundingBox();
    const infoWidth = await shipmentInfo(page).evaluate((element) => element.scrollWidth);
    expect(infoWidth).toBeLessThanOrEqual(Math.ceil(cardBox!.width));
    expect(errors).toEqual([]);
});

test("QA-B8: müşteri gönderisi dizi/sayı/küçük harf/boşluk takip no ise kart görünür, kargo bölümü yok", async ({ page }) => {
    const { errors } = await openMyOrders(page, [
        order(1, { status: "SHIPPED", shipment: [] as unknown as CustomerShipment }),
        order(2, { status: "SHIPPED", shipment: 7 as unknown as CustomerShipment }),
        order(3, { status: "SHIPPED", shipment: { ...customerShipment(), status: "in_transit" } as unknown as CustomerShipment }),
        order(4, { status: "SHIPPED", shipment: customerShipment({ trackingNumber: "   " }) }),
        order(5, { status: "SHIPPED", shipment: customerShipment({ trackingUrl: 5 as unknown as string }) }),
    ]);

    await expect(card(page)).toHaveCount(5);
    await expect(shipmentInfo(page)).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("QA-C5 müşteri: ödeme bekleyen sipariş iptali (onay reddi istek göndermez, kabul iptal eder)", async ({ page }) => {
    const { state, errors } = await openMyOrders(page, [order(1, { status: "PAYMENT_PENDING" })]);

    page.once("dialog", (prompt) => { void prompt.dismiss(); });
    await page.getByRole("button", { name: "Siparişi iptal et" }).click();
    expect(state.cancelled).toEqual([]);

    page.once("dialog", (prompt) => { void prompt.accept(); });
    await page.getByRole("button", { name: "Siparişi iptal et" }).click();
    await expect(page.getByRole("status")).toContainText("Sipariş iptal edildi");
    await expect(page.getByRole("button", { name: "Siparişi iptal et" })).toHaveCount(0);
    expect(state.cancelled).toEqual([1]);
    expect(errors).toEqual([]);
});
