import { createServer, type Server, type Socket } from "node:net";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it } from "vitest";
import { ProviderNotReachedError } from "../src/provider/iyzico.gateway.js";
import { IyzicoHttpClient } from "../src/provider/iyzico-http.client.js";

/**
 * iyzico sandbox'ın davranışı: uzun aradan sonraki ilk bağlantıyı kabul edip TLS el sıkışmasını
 * bitirmiyor. İstemci bunu kısa sürede fark edip hemen tekrar denemeli; istek gövdesi hiç gitmediği
 * için hata "ulaşılamadı" (para çekilmedi) sayılmalı.
 */
describe("iyzico HTTP istemcisi: el sıkışma zaman aşımı", () => {
  let server: Server | undefined;
  const sockets: Socket[] = [];

  afterEach(async () => {
    sockets.forEach((socket) => socket.destroy());
    await new Promise((resolve) => server?.close(resolve));
  });

  async function startServer(onConnection: (socket: Socket) => void): Promise<{ url: string; connections: () => number }> {
    let count = 0;
    server = createServer((socket) => {
      count++;
      sockets.push(socket);
      onConnection(socket);
    });
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const { port } = server.address() as AddressInfo;
    return { url: `https://127.0.0.1:${port}`, connections: () => count };
  }

  it("el sıkışmayı bitirmeyen sunucuyu ~1 sn'de bırakır ve hemen tekrar dener", async () => {
    const { url, connections } = await startServer(() => {
      // Hiçbir şey yapma: ClientHello'ya cevap verme.
    });
    const client = new IyzicoHttpClient(url, "k", "s", 60_000, 300);

    const started = Date.now();
    const error = await client.post("/payment/auth", { a: 1 }).catch((e: unknown) => e);
    const elapsed = Date.now() - started;

    expect(error).toBeInstanceOf(ProviderNotReachedError);
    expect(connections()).toBe(3); // 3 deneme
    // 3 × 300 ms el sıkışma + 100 + 200 ms bekleme ≈ 1,2 sn; 60 sn'lik okuma süresine takılmadı.
    expect(elapsed).toBeLessThan(2_500);
  });

  it("bağlantıyı hemen kesen sunucuda da 'ulaşılamadı' der (para çekilmedi)", async () => {
    const { url, connections } = await startServer((socket) => socket.destroy());
    const client = new IyzicoHttpClient(url, "k", "s", 60_000, 1_000);

    const error = await client.post("/payment/auth", { a: 1 }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ProviderNotReachedError);
    expect(connections()).toBe(3);
  });
});
