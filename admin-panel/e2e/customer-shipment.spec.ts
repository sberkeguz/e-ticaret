import { expect, test, type Page } from "@playwright/test";
import type { CustomerShipment, OrderResponse } from "../src/types/api";
import { mockBackend, newState, order, trackConsoleErrors } from "./mock-api";

/** Siparişlerim sayfasındaki kargo bilgisi (takip numarası, firma, takip bağlantısı). */

const TRACKING = "HK987654321";

const shipment = (overrides: Partial<CustomerShipment> = {}): CustomerShipment => ({
    status: "CREATED", carrierName: "Hızlı Kargo", trackingNumber: TRACKING,
    trackingUrl: "https://kargo.example.com/takip?ref=abc", statusAt: "2026-11-23T09:00:00Z", ...overrides,
});

const card = (page: Page) => page.getByRole("article");
const shipmentInfo = (page: Page) => page.getByRole("region", { name: "Kargo bilgisi" });

async function openMyOrders(page: Page, orders: OrderResponse[]) {
    const errors = trackConsoleErrors(page);
    const state = await mockBackend(page, newState({ orders }));
    await page.goto("/orders");
    await expect(card(page).first()).toBeVisible();
    return { state, errors };
}

test("B1/B4: hazırlanan ve gönderisi olmayan siparişte kargo bölümü yok, adım çubuğu bugünkü gibi", async ({ page }) => {
    const { state, errors } = await openMyOrders(page, [order(1, { status: "PREPARING", shipment: null }), order(2, { status: "PREPARING" })]);

    await expect(card(page)).toHaveCount(2);
    await expect(page.locator(".order-track")).toHaveCount(2);
    await expect(shipmentInfo(page)).toHaveCount(0);
    await expect(page.getByRole("button", { name: /Kargo/ })).toHaveCount(0);
    expect(state.unknown).toEqual([]);
    expect(errors).toEqual([]);
});

test("B2: kargoya verilen siparişte firma, takip numarası ve https takip bağlantısı görünür", async ({ page }) => {
    const { errors } = await openMyOrders(page, [order(1, { status: "SHIPPED", shipment: shipment() })]);

    const info = shipmentInfo(page);
    await expect(info).toContainText("Hızlı Kargo");
    await expect(info).toContainText(TRACKING);
    // Başlık adım çubuğundaki "Kargoya verildi"yi tekrarlamaz.
    await expect(info).toContainText("Kargonuz kargo firmasına teslim edildi");
    await expect(info).not.toContainText("Kargoya verildi");
    const link = info.getByRole("link", { name: "Kargonuzu takip edin" });
    await expect(link).toHaveAttribute("href", "https://kargo.example.com/takip?ref=abc");
    await expect(link).toHaveAttribute("target", "_blank");
    await expect(link).toHaveAttribute("rel", "noopener noreferrer");
    expect(errors).toEqual([]);
});

test("B2: takip bağlantısı yoksa ya da https değilse bağlantı gösterilmez, numara görünür", async ({ page }) => {
    const { errors } = await openMyOrders(page, [
        order(1, { status: "SHIPPED", shipment: shipment({ trackingUrl: null }) }),
        order(2, { status: "SHIPPED", shipment: shipment({ trackingUrl: "javascript:alert(1)" }) }),
        order(3, { status: "SHIPPED", shipment: shipment({ trackingUrl: "http://kargo.example.com/takip" }) }),
    ]);

    await expect(shipmentInfo(page)).toHaveCount(3);
    for (const info of await shipmentInfo(page).all()) await expect(info).toContainText(TRACKING);
    await expect(page.getByRole("link", { name: "Kargonuzu takip edin" })).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("B3: yolda ve teslim edilemedi mesajları; iade/iptal sözü geçmez", async ({ page }) => {
    const { errors } = await openMyOrders(page, [
        order(1, { status: "SHIPPED", shipment: shipment({ status: "IN_TRANSIT" }) }),
        order(2, { status: "SHIPPED", shipment: shipment({ status: "DELIVERY_FAILED" }) }),
    ]);

    const [transit, failed] = await shipmentInfo(page).all();
    await expect(transit!).toContainText("Kargonuz yolda");
    await expect(failed!).toContainText("Teslimat yapılamadı, kargo firması tekrar deneyecek.");
    for (const info of [transit!, failed!]) {
        await expect(info).not.toContainText(/iade|iptal/i);
    }
    expect(errors).toEqual([]);
});

test("B7: teslim edilen siparişte takip numarası görünür", async ({ page }) => {
    const { errors } = await openMyOrders(page, [order(1, { status: "DELIVERED", shipment: shipment({ status: "DELIVERED" }) })]);

    await expect(shipmentInfo(page)).toContainText("Teslim edildi");
    await expect(shipmentInfo(page)).toContainText(TRACKING);
    expect(errors).toEqual([]);
});

test("B4: yönetici alanları (FAILED, REQUESTED, failureReason, requestNo, canShip) müşteriye gösterilmez", async ({ page }) => {
    const adminLike = (value: Record<string, unknown>) => value as unknown as CustomerShipment;
    const { errors } = await openMyOrders(page, [
        { ...order(1, { status: "PREPARING" }), canShip: true, shipment: adminLike({
            status: "FAILED", carrierName: "Hızlı Kargo", trackingNumber: TRACKING, trackingUrl: null,
            statusAt: "2026-11-23T09:00:00Z", requestNo: 2, failureReason: "Adres doğrulanamadı", canRetry: true,
        }) } as OrderResponse,
        order(2, { status: "PREPARING", shipment: adminLike({ ...shipment(), status: "REQUESTED", requestNo: 1 }) }),
    ]);

    await expect(card(page)).toHaveCount(2);
    await expect(shipmentInfo(page)).toHaveCount(0);
    await expect(page.getByText("Adres doğrulanamadı")).toHaveCount(0);
    await expect(page.getByText(TRACKING)).toHaveCount(0);
    await expect(page.getByRole("button", { name: /Kargoya ver|Tekrar dene/ })).toHaveCount(0);
    expect(errors).toEqual([]);
});

test("B6: kargo bölümünde alıcı adı/adresi yok, sayfa adresi takip numarası içermez", async ({ page }) => {
    const { errors } = await openMyOrders(page, [order(1, { status: "SHIPPED", shipment: shipment() })]);

    const info = shipmentInfo(page);
    await expect(info).toContainText(TRACKING);
    for (const personal of ["Ayşe", "Yılmaz", "Moda", "Test sok.", "Kadıköy", "0555"]) {
        await expect(info).not.toContainText(personal);
    }
    await info.getByRole("link", { name: "Kargonuzu takip edin" }).hover();
    expect(page.url()).not.toContain(TRACKING);
    expect(errors).toEqual([]);
});

test("B8/C1: bozuk kargo verisi kartı bozmaz, kargo bölümü gösterilmez, konsola hata yazılmaz", async ({ page }) => {
    const broken = (value: unknown) => value as CustomerShipment;
    const { errors } = await openMyOrders(page, [
        order(1, { status: "SHIPPED", shipment: broken({ ...shipment(), status: "LOST" }) }),
        order(2, { status: "SHIPPED", shipment: broken({ ...shipment(), trackingNumber: "  " }) }),
        order(3, { status: "SHIPPED", shipment: broken({ ...shipment(), trackingNumber: 42 }) }),
        order(4, { status: "SHIPPED", shipment: broken("CREATED") }),
        order(5, { status: "SHIPPED", shipment: broken({ ...shipment(), trackingUrl: { href: "https://x" } }) }),
    ]);

    await expect(card(page)).toHaveCount(5);
    for (const item of await card(page).all()) await expect(item).toContainText("Elma");
    await expect(page.locator(".order-track")).toHaveCount(5);
    await expect(shipmentInfo(page)).toHaveCount(0);
    expect(errors).toEqual([]);
});
