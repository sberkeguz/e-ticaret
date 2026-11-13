import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.js";
import { IyzicoKeepWarm } from "../src/provider/iyzico-keep-warm.js";

/** Isıtma isteği: iyzico'ya yalnızca HEAD / gider; ödeme API'sine dokunmaz, hatada sessiz kalır. */
describe("iyzico bağlantısını sıcak tutma", () => {
  let server: Server | undefined;
  afterEach(() => new Promise((resolve) => (server ? server.close(resolve) : resolve(undefined))));

  it("HEAD / isteği atar ve süreyi döner", async () => {
    const seen: string[] = [];
    server = createServer((req, res) => { seen.push(`${req.method} ${req.url}`); res.end(); });
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const base = loadConfig({});
    const keepWarm = new IyzicoKeepWarm({ ...base, iyzico: { ...base.iyzico, baseUrl: `http://127.0.0.1:${(server.address() as AddressInfo).port}`, keepWarmIntervalMs: 0 } });

    const ms = await keepWarm.ping();

    expect(ms).toBeGreaterThanOrEqual(0);
    expect(seen).toEqual(["HEAD /"]);
  });

  it("ilk bağlantı düşerse hemen tekrar dener; ikincisi geçerse yol ısınmış olur", async () => {
    let connections = 0;
    server = createServer((_req, res) => res.end());
    // iyzico'nun soğuk yol davranışı: ilk bağlantı cevapsız kesilir, sonrakiler normal.
    server.on("connection", (socket) => {
      connections++;
      if (connections === 1) socket.destroy();
    });
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const base = loadConfig({});
    const keepWarm = new IyzicoKeepWarm({ ...base, iyzico: { ...base.iyzico, baseUrl: `http://127.0.0.1:${(server.address() as AddressInfo).port}`, keepWarmIntervalMs: 0 } });

    expect(await keepWarm.ping()).toBeGreaterThanOrEqual(0);
    expect(connections).toBe(2);
  });

  it("ulaşılamazsa hata fırlatmaz, -1 döner", async () => {
    const base = loadConfig({});
    const keepWarm = new IyzicoKeepWarm({ ...base, iyzico: { ...base.iyzico, baseUrl: "http://127.0.0.1:1", keepWarmIntervalMs: 0 } });
    expect(await keepWarm.ping()).toBe(-1);
  });
});
