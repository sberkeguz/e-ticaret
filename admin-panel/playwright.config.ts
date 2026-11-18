import { defineConfig, devices } from "@playwright/test";

/**
 * Arayüz testleri. Backend çalıştırılmaz: her test /api isteklerini e2e/mock-api.ts ile cevaplar,
 * böylece testler her yerde (CI dahil) aynı sonucu verir ve gerçek siparişlere dokunmaz.
 * Çalıştırma: npm run test:e2e   (ilk kez: npx playwright install chromium)
 */
const PORT = 5179;

export default defineConfig({
    testDir: "e2e",
    fullyParallel: true,
    forbidOnly: Boolean(process.env.CI),
    retries: process.env.CI ? 1 : 0,
    reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
    use: {
        baseURL: `http://localhost:${PORT}`,
        locale: "tr-TR",
        timezoneId: "Europe/Istanbul",
        trace: "retain-on-failure",
    },
    projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
    webServer: {
        command: `npm run dev -- --port ${PORT} --strictPort`,
        url: `http://localhost:${PORT}`,
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
    },
});
