import { expect, test, type Locator, type Page } from "@playwright/test";
import type { AdminOrderResponse, AdminShipment, ShipmentStatus } from "../src/types/api";
import { adminOrder, mockBackend, newState, trackConsoleErrors, type MockAnswer, type MockState } from "./mock-api";

/**
 * Yönetici sipariş listesindeki kargo sütunu: "Kargoya ver", "Tekrar dene", gönderi durumu ve
 * gönderili siparişte durum kutusunun kilitlenmesi. Backend sahte (mock-api.ts).
 */

const shipment = (status: ShipmentStatus, overrides: Partial<AdminShipment> = {}): AdminShipment => ({
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

/** Kargoya verilebilir sipariş (hazırlanıyor, ödendi, gönderi yok: backend canShip=true der). */
const shippable = (id: number, overrides: Partial<AdminOrderResponse> = {}) => adminOrder(id, { canShip: true, ...overrides });

const row = (page: Page, id: number) =>
    page.getByRole("row").filter({ has: page.getByRole("cell", { name: `#${id}`, exact: true }) });
/** Sütunlar: No, Kullanıcı, Alıcı, Ürünler, Tutar, Tarih, Durum, Kargo. */
const shipmentCell = (page: Page, id: number) => row(page, id).getByRole("cell").nth(7);
const statusSelect = (page: Page, id: number) => row(page, id).getByRole("combobox");
const dialog = (page: Page) => page.getByRole("dialog");
const banner = (page: Page) => page.getByRole("alert");

async function openAdminOrders(page: Page, orders: AdminOrderResponse[], extra: Partial<MockState> = {}) {
    const errors = trackConsoleErrors(page);
    const state = await mockBackend(page, newState({ username: "admin", role: "ADMIN", adminOrders: orders, ...extra }));
    await page.goto("/admin/orders");
    await expect(row(page, orders[0]!.id)).toBeVisible();
    // Geliştirme modunda (React StrictMode) ilk yükleme iki istek olabilir: sonraki yüklemeler buna göre sayılır.
    const initialLoads = state.listRequests;
    return { state, errors, initialLoads };
}

/** Kargoya ver → onay penceresinde onay. */
async function shipFromRow(page: Page, id: number) {
    await shipmentCell(page, id).getByRole("button", { name: "Kargoya ver" }).click();
    await dialog(page).getByRole("button", { name: "Evet, kargoya ver" }).click();
}

const requested = (base: AdminOrderResponse, requestNo = 1): AdminOrderResponse => ({
    ...base, canShip: false, shipment: shipment("REQUESTED", { requestNo }),
});

function deferred() {
    let release: (value: MockAnswer) => void = () => {};
    const gate = new Promise<MockAnswer>((resolve) => { release = resolve; });
    return { gate, release };
}

test("A1/C2: 'Kargoya ver' yalnız backend canShip=true dediği, hazırlanan ve gönderisiz siparişte görünür", async ({ page }) => {
    const { state, errors } = await openAdminOrders(page, [
        shippable(1),
        adminOrder(2, { canShip: false }),
        shippable(3, { status: "PAYMENT_PENDING" }),
        shippable(4, { shipment: shipment("REQUESTED") }),
        adminOrder(5, { canShip: "true" as unknown as boolean }),
        adminOrder(6, { canShip: undefined }),
        shippable(7, { status: "SHIPPED" }),
    ]);

    await expect(shipmentCell(page, 1).getByRole("button", { name: "Kargoya ver" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Kargoya ver" })).toHaveCount(1);
    expect(state.shipmentRequests).toEqual([]);
    expect(state.unknown).toEqual([]);
    expect(errors).toEqual([]);
});

test("A2: onay penceresi açılır, ilk odak Vazgeç'tedir; Vazgeç ve Esc istek göndermez", async ({ page }) => {
    const { state, errors } = await openAdminOrders(page, [shippable(1)]);

    await shipmentCell(page, 1).getByRole("button", { name: "Kargoya ver" }).click();
    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page).getByRole("button", { name: "Vazgeç" })).toBeFocused();
    await dialog(page).getByRole("button", { name: "Vazgeç" }).click();
    await expect(dialog(page)).toBeHidden();

    await shipmentCell(page, 1).getByRole("button", { name: "Kargoya ver" }).click();
    await expect(dialog(page)).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(dialog(page)).toBeHidden();

    expect(state.shipmentRequests).toEqual([]);
    expect(errors).toEqual([]);
});

test("A3/A4: istek sürerken düğmeler kilitli ve pencere kapanmaz; cevap gelince satır 'Kargo hazırlanıyor' olur", async ({ page }) => {
    const { gate, release } = deferred();
    const base = shippable(1);
    const { state, errors, initialLoads } = await openAdminOrders(page, [base], { shipmentAnswers: [{ gate }] });

    await shipFromRow(page, 1);
    const busy = dialog(page).getByRole("button", { name: "Kargoya veriliyor…" });
    await expect(busy).toBeDisabled();
    await expect(dialog(page).getByRole("button", { name: "Vazgeç" })).toBeDisabled();
    await page.keyboard.press("Escape");
    await expect(dialog(page)).toBeVisible();

    release({ status: 202, body: requested(base) });
    await expect(dialog(page)).toBeHidden();
    await expect(shipmentCell(page, 1)).toContainText("Kargo hazırlanıyor");
    await expect(shipmentCell(page, 1).getByRole("button", { name: "Kargoya ver" })).toHaveCount(0);
    expect(state.shipmentRequests).toEqual([{ path: "/api/admin/orders/1/shipment", body: null }]);
    expect(state.listRequests).toBe(initialLoads); // geçerli cevap satırı günceller, liste yeniden yüklenmez
    expect(errors).toEqual([]);
});

test("çift tık tek kargo isteği gönderir", async ({ page }) => {
    const { state, errors } = await openAdminOrders(page, [shippable(1)]);

    await shipmentCell(page, 1).getByRole("button", { name: "Kargoya ver" }).click();
    await dialog(page).getByRole("button", { name: "Evet, kargoya ver" }).dblclick();

    await expect(shipmentCell(page, 1)).toContainText("Kargo hazırlanıyor");
    expect(state.shipmentRequests).toHaveLength(1);
    expect(errors).toEqual([]);
});

test("409 SHIPMENT_EXISTS: sabit mesaj bantta, liste yeniden yüklenir", async ({ page }) => {
    const base = shippable(1);
    const { state, errors, initialLoads } = await openAdminOrders(page, [base], {
        shipmentAnswers: [{ status: 409, body: { status: 409, code: "SHIPMENT_EXISTS", message: "Shipment already exists" } }],
    });
    state.adminOrders = [requested(base)]; // başka sekmede kargoya verilmiş

    await shipFromRow(page, 1);

    await expect(banner(page)).toHaveText("#1: Bu sipariş zaten kargoya verildi.");
    await expect.poll(() => state.listRequests).toBe(initialLoads + 1);
    await expect(shipmentCell(page, 1)).toContainText("Kargo hazırlanıyor");
    expect(errors).toEqual([]);
});

test("409 SHIPMENT_NOT_ALLOWED: backend mesajı bantta, liste yeniden yüklenir", async ({ page }) => {
    const { state, errors, initialLoads } = await openAdminOrders(page, [shippable(1)], {
        shipmentAnswers: [{ status: 409, body: { status: 409, code: "SHIPMENT_NOT_ALLOWED", message: "Ödemesi alınmamış sipariş kargoya verilemez." } }],
    });
    state.adminOrders = [adminOrder(1, { canShip: false })];

    await shipFromRow(page, 1);

    await expect(banner(page)).toHaveText("#1: Ödemesi alınmamış sipariş kargoya verilemez.");
    await expect.poll(() => state.listRequests).toBe(initialLoads + 1);
    await expect(shipmentCell(page, 1).getByRole("button", { name: "Kargoya ver" })).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("A6: altı gönderi durumu Türkçe etiketle görünür, ham kod görünmez", async ({ page }) => {
    const statuses: [ShipmentStatus, string][] = [
        ["REQUESTED", "Kargo hazırlanıyor"], ["CREATED", "Kargoya verildi"], ["IN_TRANSIT", "Yolda"],
        ["DELIVERED", "Teslim edildi"], ["DELIVERY_FAILED", "Teslim edilemedi"], ["FAILED", "Kargo oluşturulamadı"],
    ];
    const { errors } = await openAdminOrders(page, statuses.map(([status], index) => adminOrder(index + 1, { shipment: shipment(status) })));

    for (const [index, [, label]] of statuses.entries()) {
        await expect(shipmentCell(page, index + 1)).toContainText(label);
    }
    for (const [code] of statuses) {
        await expect(page.getByRole("table")).not.toContainText(code);
    }
    expect(errors).toEqual([]);
});

test("A5: takip numarası ve kargo firması görünür, Kopyala panoya yazar", async ({ page }) => {
    await page.addInitScript(() => {
        Object.defineProperty(navigator, "clipboard", {
            configurable: true,
            value: { writeText: (text: string) => { (window as unknown as { copied: string }).copied = text; return Promise.resolve(); } },
        });
    });
    const { errors } = await openAdminOrders(page, [adminOrder(1, { status: "SHIPPED", shipment: shipment("CREATED") })]);

    const cell = shipmentCell(page, 1);
    await expect(cell.locator("code")).toHaveText("HK123456789");
    await expect(cell).toContainText("Hızlı Kargo");
    await cell.getByRole("button", { name: "Kopyala" }).click();
    await expect(cell).toContainText("Kopyalandı");
    expect(await page.evaluate(() => (window as unknown as { copied: string }).copied)).toBe("HK123456789");
    expect(errors).toEqual([]);
});

test("A5: pano kullanılamıyorsa Kopyala düğmesi yok, takip numarası yine görünür", async ({ page }) => {
    await page.addInitScript(() => {
        Object.defineProperty(navigator, "clipboard", { configurable: true, value: undefined });
    });
    const { errors } = await openAdminOrders(page, [adminOrder(1, { status: "SHIPPED", shipment: shipment("CREATED") })]);

    await expect(shipmentCell(page, 1).locator("code")).toHaveText("HK123456789");
    await expect(shipmentCell(page, 1).getByRole("button", { name: "Kopyala" })).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("Durum kutusu: 'Kargoya verildi' ve 'Teslim edildi' hiçbir siparişte elle seçilemez; SHIPPED/DELIVERED ve gönderili siparişte kutu salt okunur; FAILED'da iptal edilebilir", async ({ page }) => {
    const optionValues = (id: number) => statusSelect(page, id).locator("option").evaluateAll(
        (options) => options.map((option) => (option as HTMLOptionElement).value));
    const { errors } = await openAdminOrders(page, [
        adminOrder(1, { status: "SHIPPED", shipment: shipment("CREATED") }),
        adminOrder(2, { shipment: shipment("FAILED", { canRetry: true }) }),
        adminOrder(3),
        adminOrder(4, { shipment: shipment("REQUESTED") }),
        adminOrder(5, { status: "PAYMENT_PENDING" }),
        adminOrder(6, { status: "SHIPPED" }), // eski sipariş: gönderi kaydı yok ama kargoya verilmiş
        adminOrder(7, { status: "DELIVERED" }),
    ]);

    for (const id of [1, 4, 6, 7]) {
        await expect(statusSelect(page, id)).toBeDisabled();
        await expect(statusSelect(page, id)).toHaveAttribute("title", "Kargo durumu kargo firmasından güncellenir.");
    }
    await expect(statusSelect(page, 2)).toBeEnabled();
    expect(await optionValues(2)).toEqual(["PREPARING", "CANCELLED"]);
    await expect(statusSelect(page, 3)).toBeEnabled();
    expect(await optionValues(3)).toEqual(["PREPARING", "CANCELLED"]);
    await expect(statusSelect(page, 5)).toBeEnabled();
    expect(await optionValues(5)).toEqual(["PAYMENT_PENDING", "PREPARING", "CANCELLED"]);

    // Not tablonun üstünde bir kez yazar, satırlarda tekrarlanmaz.
    await expect(page.getByText("Kargo durumu kargo firmasından güncellenir.")).toHaveCount(1);
    await expect(page.getByRole("table")).not.toContainText("Kargo durumu kargo firmasından güncellenir.");
    expect(errors).toEqual([]);
});

test("A8: PATCH 409 ORDER_HAS_SHIPMENT bantta görünür, kutu eski değerine döner", async ({ page }) => {
    const { state, errors } = await openAdminOrders(page, [adminOrder(1)], {
        statusPatchAnswers: [{ status: 409, body: { status: 409, code: "ORDER_HAS_SHIPMENT", message: "Bu siparişin kargo kaydı var, durum kargo firmasından güncellenir." } }],
    });
    page.once("dialog", (confirm) => { void confirm.accept(); }); // iptal onayı

    await statusSelect(page, 1).selectOption("CANCELLED");

    await expect(banner(page)).toHaveText("#1: Bu siparişin kargo kaydı var, durum kargo firmasından güncellenir.");
    await expect(statusSelect(page, 1)).toHaveValue("PREPARING");
    expect(state.statusPatches).toEqual([{ id: 1, status: "CANCELLED" }]);
    expect(errors).toEqual([]);
});

test("A9/A10: FAILED'da sebep görünür; Tekrar dene expectedRequestNo gönderir, çift tık tek istek", async ({ page }) => {
    const { gate, release } = deferred();
    const base = adminOrder(1, { shipment: shipment("FAILED", { requestNo: 2, canRetry: true, failureReason: "Adres kargo firmasınca doğrulanamadı." }) });
    const { state, errors } = await openAdminOrders(page, [base, adminOrder(2, { shipment: shipment("FAILED", { canRetry: true }) })],
        { shipmentAnswers: [{ gate }] });

    await expect(shipmentCell(page, 1)).toContainText("Adres kargo firmasınca doğrulanamadı.");
    await expect(shipmentCell(page, 2)).toContainText("Kargo firması gönderiyi oluşturamadı.");

    await shipmentCell(page, 1).getByRole("button", { name: "Tekrar dene" }).click();
    await expect(dialog(page).getByRole("button", { name: "Vazgeç" })).toBeFocused();
    await dialog(page).getByRole("button", { name: "Evet, tekrar dene" }).dblclick();
    await expect(dialog(page).getByRole("button", { name: "Yeniden deneniyor…" })).toBeDisabled();

    release({ status: 202, body: requested(base, 3) });
    await expect(shipmentCell(page, 1)).toContainText("Kargo hazırlanıyor");
    await expect(shipmentCell(page, 1).getByRole("button", { name: "Tekrar dene" })).toHaveCount(0);
    expect(state.shipmentRequests).toEqual([{ path: "/api/admin/orders/1/shipment/retry", body: { expectedRequestNo: 2 } }]);
    expect(errors).toEqual([]);
});

test("Tekrar dene yalnız FAILED ve canRetry=true iken görünür", async ({ page }) => {
    const others: ShipmentStatus[] = ["REQUESTED", "CREATED", "IN_TRANSIT", "DELIVERED", "DELIVERY_FAILED"];
    const { errors } = await openAdminOrders(page, [
        adminOrder(1, { shipment: shipment("FAILED", { canRetry: false }) }),
        ...others.map((status, index) => adminOrder(index + 2, { shipment: shipment(status, { canRetry: true }) })),
    ]);

    await expect(shipmentCell(page, 1)).toContainText("Kargo oluşturulamadı");
    await expect(page.getByRole("button", { name: "Tekrar dene" })).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("A11: 500 ve bağlantı kopması bantta görünür, düğme yeniden kullanılabilir", async ({ page }) => {
    const { state, errors, initialLoads } = await openAdminOrders(page, [shippable(1)], {
        shipmentAnswers: [
            { status: 500, body: { status: 500, code: "INTERNAL_ERROR", message: "Beklenmeyen bir hata oluştu." } },
            "abort",
            { status: 500, body: "<html>Bad Gateway</html>" },
        ],
    });

    await shipFromRow(page, 1);
    await expect(banner(page)).toHaveText("#1: Beklenmeyen bir hata oluştu.");
    await expect(dialog(page)).toBeHidden();

    await shipFromRow(page, 1);
    await expect(banner(page)).toHaveText("#1: Bağlantı kurulamadı, tekrar deneyin.");

    await shipFromRow(page, 1);
    await expect(banner(page)).toHaveText("#1: Kargo isteği gönderilemedi.");
    await expect(shipmentCell(page, 1).getByRole("button", { name: "Kargoya ver" })).toBeEnabled();
    expect(state.shipmentRequests).toHaveLength(3);
    expect(state.listRequests).toBe(initialLoads);
    expect(errors).toEqual([]);
});

test("A11: cevap 15 sn gelmezse zaman aşımı mesajı, düğme yeniden kullanılabilir", async ({ page }) => {
    await page.clock.install();
    const { errors } = await openAdminOrders(page, [shippable(1)], { shipmentAnswers: ["hang"] });

    await shipFromRow(page, 1);
    await expect(dialog(page).getByRole("button", { name: "Kargoya veriliyor…" })).toBeDisabled();
    await page.clock.fastForward(15_000);

    await expect(banner(page)).toHaveText("#1: Sunucu zamanında yanıt vermedi. Sayfayı yenileyip kargo durumunu kontrol edin.");
    await expect(shipmentCell(page, 1).getByRole("button", { name: "Kargoya ver" })).toBeEnabled();
    expect(errors).toEqual([]);
});

test("C3: kargo ucu kapalıysa (405, ENDPOINT_NOT_FOUND, JSON olmayan 404) özellik kapalı mesajı; RESOURCE_NOT_FOUND ise 'Sipariş bulunamadı.'", async ({ page }) => {
    const { errors } = await openAdminOrders(page, [shippable(1)], {
        shipmentAnswers: [
            { status: 405, body: { status: 405, code: "METHOD_NOT_ALLOWED", message: "Request method 'POST' is not supported" } },
            { status: 404, body: { status: 404, code: "ENDPOINT_NOT_FOUND", message: "No endpoint" } },
            { status: 404, body: "<html>Not Found</html>" },
            { status: 404, body: { status: 404, code: "RESOURCE_NOT_FOUND", message: "Order 1 not found" } },
        ],
    });

    for (const expected of ["Kargo özelliği henüz açık değil.", "Kargo özelliği henüz açık değil.", "Kargo özelliği henüz açık değil.", "Sipariş bulunamadı."]) {
        await shipFromRow(page, 1);
        await expect(banner(page)).toHaveText(`#1: ${expected}`);
        await expect(shipmentCell(page, 1).getByRole("button", { name: "Kargoya ver" })).toBeEnabled();
    }
    expect(errors).toEqual([]);
});

test("A12: 403 ACCESS_DENIED bantta görünür, giriş sayfasına atılmaz", async ({ page }) => {
    const { errors } = await openAdminOrders(page, [shippable(1)], {
        shipmentAnswers: [{ status: 403, body: { status: 403, code: "ACCESS_DENIED", message: "Bu işlem için yetkiniz yok." } }],
    });

    await shipFromRow(page, 1);

    await expect(banner(page)).toHaveText("#1: Bu işlem için yetkiniz yok.");
    await expect(page).toHaveURL(/\/admin\/orders$/);
    expect(errors).toEqual([]);
});

test("C1: bozuk kargo verisi satırı bozmaz, kargo arayüzü gösterilmez, konsola hata yazılmaz", async ({ page }) => {
    const broken = (value: unknown) => value as AdminShipment;
    const { state, errors } = await openAdminOrders(page, [
        adminOrder(1, { canShip: true, shipment: broken({ status: "LOST", requestNo: 1, statusAt: "2026-11-23T09:00:00Z" }) }),
        adminOrder(2, { canShip: true, shipment: broken("REQUESTED") }),
        adminOrder(3, { shipment: broken({ ...shipment("FAILED", { canRetry: true }), requestNo: "2" }) }),
        adminOrder(4, { shipment: broken({ ...shipment("CREATED"), trackingNumber: 123 }) }),
    ]);

    for (const id of [1, 2, 3, 4]) {
        await expect(row(page, id)).toContainText("Elma × 2");
        await expect(shipmentCell(page, id).getByRole("button")).toHaveCount(0);
        await expect(shipmentCell(page, id)).toHaveText("—");
        // Gönderi kaydı var ama okunamıyor: elle kargo durumu verilmesin diye kutu kilitli kalır.
        await expect(statusSelect(page, id)).toBeDisabled();
    }
    expect(state.unknown).toEqual([]);
    expect(errors).toEqual([]);
});

test("başarılı ama okunamayan cevapta liste yeniden yüklenir", async ({ page }) => {
    const base = shippable(1);
    const { state, errors, initialLoads } = await openAdminOrders(page, [base], { shipmentAnswers: [{ status: 202, body: { ok: true } }] });
    state.adminOrders = [requested(base)];

    await shipFromRow(page, 1);

    await expect.poll(() => state.listRequests).toBe(initialLoads + 1);
    await expect(shipmentCell(page, 1)).toContainText("Kargo hazırlanıyor");
    await expect(banner(page)).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("A13: sayfa yenilenince kargo firmasından gelen yeni durum görünür", async ({ page }) => {
    const base = requested(adminOrder(1));
    const { state, errors } = await openAdminOrders(page, [base]);
    await expect(shipmentCell(page, 1)).toContainText("Kargo hazırlanıyor");

    state.adminOrders = [{ ...base, status: "SHIPPED", shipment: shipment("CREATED") }];
    await page.reload();

    await expect(shipmentCell(page, 1)).toContainText("Kargoya verildi");
    await expect(shipmentCell(page, 1).locator("code")).toHaveText("HK123456789");
    expect(errors).toEqual([]);
});

test("A14: arama ve sayfalamadan sonra da kargoya verilen sipariş doğru olur", async ({ page }) => {
    const orders = Array.from({ length: 51 }, (_, index) => adminOrder(index + 1));
    orders[29] = shippable(30, { firstName: "Zeynep" });
    orders[50] = shippable(51);
    const { state, errors } = await openAdminOrders(page, orders);

    await page.getByRole("button", { name: "Sonraki ›" }).click();
    await expect(row(page, 51)).toBeVisible();
    await shipFromRow(page, 51);
    await expect(shipmentCell(page, 51)).toContainText("Kargo hazırlanıyor");

    await page.getByPlaceholder("Kullanıcı, ad, telefon veya ürün ara...").fill("Zeynep");
    await expect(page.getByRole("row")).toHaveCount(2); // başlık + tek sonuç
    await shipFromRow(page, 30);
    await expect(shipmentCell(page, 30)).toContainText("Kargo hazırlanıyor");

    expect(state.shipmentRequests.map((item) => item.path)).toEqual([
        "/api/admin/orders/51/shipment", "/api/admin/orders/30/shipment",
    ]);
    expect(errors).toEqual([]);
});

test("Sayfa açılır açılmaz 'Sonraki' basılırsa arama gecikmesi listeyi ilk sayfaya geri atmaz", async ({ page }) => {
    // Aramanın 300 ms'lik zamanlayıcısı 1,5 sn'ye uzatılır: "Sonraki" kesin o çalışmadan basılmış olur
    // (CI'da hızlı makinede kendiliğinden olan yarış).
    await page.addInitScript(() => {
        const original = window.setTimeout;
        window.setTimeout = ((handler: TimerHandler, timeout?: number, ...args: unknown[]) =>
            original(handler, timeout === 300 ? 1_500 : timeout, ...args)) as typeof window.setTimeout;
    });
    const orders = Array.from({ length: 51 }, (_, index) => adminOrder(index + 1));
    const { state, errors } = await openAdminOrders(page, orders);

    await page.getByRole("button", { name: "Sonraki ›" }).click();
    await expect(row(page, 51)).toBeVisible();
    const loadsBefore = state.listRequests;
    await page.waitForTimeout(2_500); // uzatılmış arama gecikmesi ve olası (hatalı) yeniden yükleme geçsin
    expect(state.listRequests).toBe(loadsBefore);
    await expect(row(page, 51)).toBeVisible();
    await expect(row(page, 1)).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("PATCH cevabındaki sipariş satırı günceller (canShip dahil); okunamayan cevapta yalnız durum değişir", async ({ page }) => {
    const pending = adminOrder(1, { status: "PAYMENT_PENDING" });
    const { errors } = await openAdminOrders(page, [pending, adminOrder(2, { status: "PAYMENT_PENDING" })], {
        statusPatchAnswers: [
            { status: 200, body: { ...pending, status: "PREPARING", canShip: true } },
            { status: 200, body: { ok: true } },
        ],
    });

    await statusSelect(page, 1).selectOption("PREPARING");
    await expect(shipmentCell(page, 1).getByRole("button", { name: "Kargoya ver" })).toBeVisible();

    await statusSelect(page, 2).selectOption("PREPARING");
    await expect(statusSelect(page, 2)).toHaveValue("PREPARING");
    await expect(shipmentCell(page, 2).getByRole("button", { name: "Kargoya ver" })).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("başarılı kargo komutundan sonra odak sayfaya düşmez, yeni durum etiketine geçer", async ({ page }) => {
    const { errors } = await openAdminOrders(page, [shippable(1), adminOrder(2, { shipment: shipment("FAILED", { canRetry: true }) })]);

    await shipFromRow(page, 1);
    await expect(shipmentCell(page, 1).getByText("Kargo hazırlanıyor")).toBeFocused();

    await shipmentCell(page, 2).getByRole("button", { name: "Tekrar dene" }).click();
    await dialog(page).getByRole("button", { name: "Evet, tekrar dene" }).click();
    await expect(shipmentCell(page, 2).getByText("Kargo hazırlanıyor")).toBeFocused();
    expect(errors).toEqual([]);
});

test("başarılı kargoya ver ve tekrar dene kısa başarı bandı gösterir; sonraki hata bandı onu kaldırır", async ({ page }) => {
    const { errors } = await openAdminOrders(page, [shippable(1), adminOrder(2, { shipment: shipment("FAILED", { canRetry: true }) }), shippable(3)], {
        shipmentAnswers: [{ status: 202, body: requested(shippable(1)) }, { status: 202, body: requested(adminOrder(2), 2) },
            { status: 500, body: { status: 500, code: "INTERNAL_ERROR", message: "Beklenmeyen bir hata oluştu." } }],
    });
    const success = page.locator(".success-banner");

    await shipFromRow(page, 1);
    await expect(success).toHaveText("#1 için kargo kaydı istendi.");

    await shipmentCell(page, 2).getByRole("button", { name: "Tekrar dene" }).click();
    await dialog(page).getByRole("button", { name: "Evet, tekrar dene" }).click();
    await expect(success).toHaveText("#2 için kargo yeniden istendi.");

    await shipFromRow(page, 3);
    await expect(banner(page)).toHaveText("#3: Beklenmeyen bir hata oluştu.");
    await expect(success).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("1024 px genişlikte durum kutusu ve kargo hücresindeki metinler kesilmez", async ({ page }) => {
    await page.setViewportSize({ width: 1024, height: 768 });
    const { errors } = await openAdminOrders(page, [
        shippable(1),
        adminOrder(2, { status: "PAYMENT_EXPIRED", firstName: "Ayşegül Nur", lastName: "Karaosmanoğlu" }),
        adminOrder(3, { status: "PAYMENT_REVIEW" }),
        adminOrder(4, { status: "SHIPPED", shipment: shipment("DELIVERY_FAILED", { carrierName: "Hızlı Kargo Lojistik" }) }),
        adminOrder(5, { shipment: shipment("FAILED", { canRetry: true, failureReason: "Adres kargo firmasınca doğrulanamadı." }) }),
    ]);

    for (const id of [1, 2, 3, 4, 5]) {
        // Seçili durumun yazısı kutunun içine sığmalı (ok işareti için ~20 px pay).
        const select = await statusSelect(page, id).evaluate((element) => {
            const box = element as HTMLSelectElement;
            const style = getComputedStyle(box);
            const context = document.createElement("canvas").getContext("2d")!;
            context.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
            const text = box.selectedOptions[0]?.text ?? "";
            const needed = context.measureText(text).width + parseFloat(style.paddingLeft) + parseFloat(style.paddingRight) + 20;
            return { text, needed: Math.ceil(needed), width: box.clientWidth };
        });
        expect(select.needed, `#${id} durum kutusu: "${select.text}"`).toBeLessThanOrEqual(select.width);

        // Kargo hücresindeki hiçbir öğe hücrenin dışına taşmamalı.
        const overflow = await shipmentCell(page, id).evaluate((cell) => {
            const right = cell.getBoundingClientRect().right;
            return [...cell.querySelectorAll("*")]
                .filter((child) => child.getBoundingClientRect().right > right + 0.5 || child.scrollWidth > child.clientWidth + 1)
                .map((child) => child.textContent);
        });
        expect(overflow, `#${id} kargo hücresi`).toEqual([]);
    }
    expect(errors).toEqual([]);
});

/** Konumun gerçekten görünür olduğunu (başka bir öğenin altında kalmadığını) da denetler. */
async function visibleAtCenter(locator: Locator) {
    await expect(locator).toBeInViewport();
    return locator.evaluate((element) => {
        const box = element.getBoundingClientRect();
        const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
        return hit !== null && (hit === element || element.contains(hit));
    });
}

const scrollToBottom = (page: Page) => page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));

test("Ö1: sayfa aşağı kaydırılmışken kargo ve durum işleminin başarı/hata bandı görünür alanda kalır", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 600 });
    const orders = Array.from({ length: 30 }, (_, index) => shippable(index + 1));
    orders[27] = adminOrder(28, { status: "PAYMENT_PENDING" });
    orders[29] = adminOrder(30, { shipment: shipment("FAILED", { canRetry: true }) });
    const { errors } = await openAdminOrders(page, orders, {
        shipmentAnswers: [{ status: 202, body: requested(shippable(29)) },
            { status: 500, body: { status: 500, code: "INTERNAL_ERROR", message: "Beklenmeyen bir hata oluştu." } }],
        statusPatchAnswers: [{ status: 409, body: { status: 409, code: "CONFLICT", message: "Durum değiştirilemedi." } }],
    });
    const success = page.locator(".success-banner");

    await scrollToBottom(page);
    await expect(page.getByRole("heading", { name: "Siparişler" })).not.toBeInViewport();
    await shipFromRow(page, 29);
    await expect(success).toHaveText("#29 için kargo kaydı istendi.");
    await expect(success).toHaveAttribute("role", "status");
    expect(await visibleAtCenter(success)).toBe(true);
    await expect(row(page, 29)).toBeInViewport();

    await scrollToBottom(page);
    await shipmentCell(page, 30).getByRole("button", { name: "Tekrar dene" }).click();
    await dialog(page).getByRole("button", { name: "Evet, tekrar dene" }).click();
    await expect(banner(page)).toHaveText("#30: Beklenmeyen bir hata oluştu.");
    expect(await visibleAtCenter(banner(page))).toBe(true);

    await scrollToBottom(page);
    await statusSelect(page, 28).selectOption("PREPARING");
    await expect(banner(page)).toHaveText("#28: Durum değiştirilemedi.");
    expect(await visibleAtCenter(banner(page))).toBe(true);
    await expect(statusSelect(page, 28)).toBeInViewport();
    expect(errors).toEqual([]);
});

for (const width of [375, 1024]) {
    test(`Ö2: ${width} px genişlikte tablo en sağa kaydırılınca sipariş numarası kargo sütunuyla birlikte görünür`, async ({ page }) => {
        await page.setViewportSize({ width, height: 800 });
        const { errors } = await openAdminOrders(page, [shippable(1), adminOrder(2, { shipment: shipment("FAILED", { canRetry: true }) })]);

        const scrolled = await page.locator(".table-wrap").evaluate((wrap) => {
            wrap.scrollLeft = wrap.scrollWidth;
            return wrap.scrollLeft > 0;
        });
        expect(scrolled, "tablo yatay kaymalı").toBe(true);
        // Satırlar dikeyde görünür alana gelsin (yalnız sayfa kayar, tablonun yatay konumu değişmez).
        await scrollToBottom(page);
        expect(await page.locator(".table-wrap").evaluate((wrap) => wrap.scrollLeft + wrap.clientWidth >= wrap.scrollWidth - 1)).toBe(true);

        for (const id of [1, 2]) {
            const number = row(page, id).getByRole("cell", { name: `#${id}`, exact: true });
            expect(await visibleAtCenter(number), `#${id} numarası görünmeli`).toBe(true);
            expect(await visibleAtCenter(shipmentCell(page, id)), `#${id} kargo hücresi görünmeli`).toBe(true);
        }
        expect(errors).toEqual([]);
    });
}
