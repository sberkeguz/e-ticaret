import { expect, test, type Page } from "@playwright/test";
import { mockBackend, newState, order, type MockState } from "./mock-api";

/** Ödeme ekranında kart bilgilerini doldurur (iyzico sandbox test kartı). */
async function fillCard(page: Page) {
    await page.getByLabel("Kart üzerindeki isim").fill("Ayse Yilmaz");
    await page.getByLabel("Kart numarası").fill("5528790000000008");
    await page.getByLabel("Ay").selectOption("12");
    await page.getByLabel("Yıl").selectOption(String(new Date().getFullYear() + 2));
    await page.getByLabel("CVC").fill("123");
}

const payButton = (page: Page) => page.getByRole("button", { name: /öde$/ });

async function openCheckoutWithCart(page: Page, state: MockState) {
    state.cart = [{ productId: 1, quantity: 2 }];
    await mockBackend(page, state);
    await page.goto("/checkout");
    await expect(page.getByText("Elma × 2")).toBeVisible();
    // Profildeki adres seçili gelir (il → ilçe → mahalle listeleri yüklenince).
    await expect(page.getByLabel("Mahalle")).toHaveValue("Moda");
}

test("ana sayfa ürünleri listeler, sepete eklenen ürün sepette görünür", async ({ page }) => {
    const state = await mockBackend(page, newState());
    await page.goto("/home");

    const apple = page.getByRole("article").filter({ has: page.getByRole("heading", { name: "Elma" }) });
    await expect(apple).toBeVisible();
    await apple.hover(); // düğmeler kartın üzerine gelince görünür
    await apple.getByRole("button", { name: "Sepete ekle" }).click();
    await page.goto("/cart");

    await expect(page.getByRole("button", { name: "Satın al" })).toBeEnabled();
    await expect.poll(() => state.cart).toEqual([{ productId: 1, quantity: 1 }]);
    expect(state.unknown).toEqual([]);
});

test("2D ödeme: 'işleniyor' ekranı, sonuç gelince Siparişlerim'de onay", async ({ page }) => {
    const state = newState({ statusAnswers: [{ result: "processing" }, { result: "success" }] });
    await openCheckoutWithCart(page, state);
    await fillCard(page);

    await payButton(page).click();

    await expect(page.getByText("Ödemeniz işleniyor…").first()).toBeVisible();
    await expect(page).toHaveURL(/\/orders\?payment=success/);
    await expect(page.getByText("Ödemeniz onaylandı, siparişiniz hazırlanıyor.")).toBeVisible();
    expect(state.payments).toHaveLength(1);
    const sent = state.payments[0]!.body;
    expect(sent).toMatchObject({ forceThreeDS: false, items: [{ productId: 1, quantity: 2 }], email: "ayse@example.com" });
    expect(sent.card).toMatchObject({ number: "5528790000000008", cvc: "123" });
    expect(state.unknown).toEqual([]);
});

test("reddedilen ödeme: sebep gösterilir, sipariş tekrar ödenebilir", async ({ page }) => {
    const state = newState({ statusAnswers: [{ result: "declined", message: "Kartın limiti yetersiz" }] });
    await openCheckoutWithCart(page, state);
    await fillCard(page);
    await payButton(page).click();
    await expect(page).toHaveURL(/\/orders\?payment=declined/);
    await expect(page.getByText(/Kartın limiti yetersiz\. Ödeme tamamlanmadı, kartınızdan para çekilmedi/)).toBeVisible();

    // Sonuç backend'e işlendi: sipariş artık işlenmiyor, ödeme bekliyor. Sayfa kendiliğinden yenilenir.
    state.orders = state.orders.map((item) => ({ ...item, paymentProcessing: false }));
    await page.getByRole("button", { name: "Ödemeyi yap" }).click({ timeout: 15_000 });
    await expect(page).toHaveURL(/\/checkout\?order=100/);
    await expect(page.getByRole("heading", { name: "Siparişi öde" })).toBeVisible();
});

test("RabbitMQ kapalıyken 3D Secure'suz ödeme alınmaz, 3D Secure ile ödenebilir", async ({ page }) => {
    const state = newState({ paymentsAvailable: false });
    await openCheckoutWithCart(page, state);
    await fillCard(page);

    await expect(page.getByTestId("payment-unavailable")).toBeVisible();
    await expect(payButton(page)).toBeDisabled();

    await page.getByRole("button", { name: /3D Secure: Kapalı/ }).click();
    await expect(payButton(page)).toBeEnabled();

    // Sistem düzelince bant kalkar (sayfa birkaç saniyede bir sorar).
    state.paymentsAvailable = true;
    await page.getByRole("button", { name: /3D Secure: Açık/ }).click();
    await expect(page.getByTestId("payment-unavailable")).toBeHidden({ timeout: 15_000 });
    await expect(payButton(page)).toBeEnabled();
    expect(state.payments).toEqual([]);
});

test.describe("Siparişlerim", () => {
    test("ödemesi işlenen siparişte düğme yerine 'Ödemeniz işleniyor' görünür", async ({ page }) => {
        const state = newState({
            orders: [order(7, { paymentProcessing: true }), order(8)],
        });
        await mockBackend(page, state);
        await page.goto("/orders");

        const processing = page.locator("article").filter({ has: page.getByText("Ödemeniz işleniyor") });
        await expect(processing).toHaveCount(1);
        await expect(processing.getByRole("button", { name: "Ödemeyi yap" })).toHaveCount(0);
        await expect(page.getByRole("button", { name: "Ödemeyi yap" })).toHaveCount(1);

        // Sonuç gelince sayfa kendiliğinden yenilenir ve düğme çıkar.
        state.orders = state.orders.map((item) => ({ ...item, paymentProcessing: false }));
        await expect(page.getByRole("button", { name: "Ödemeyi yap" })).toHaveCount(2, { timeout: 15_000 });
    });

    test("bekleyen sipariş iptal edilir", async ({ page }) => {
        const state = newState({ orders: [order(9)] });
        await mockBackend(page, state);
        await page.goto("/orders");
        page.once("dialog", (dialog) => dialog.accept());

        await page.getByRole("button", { name: "Siparişi iptal et" }).click();

        await expect.poll(() => state.cancelled).toEqual([9]);
        await expect(page.getByRole("button", { name: "Siparişi iptal et" })).toHaveCount(0);
    });
});
