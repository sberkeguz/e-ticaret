import { Logger } from "@nestjs/common";
import { createHmac, randomBytes } from "node:crypto";
import { request } from "node:https";
import type { TLSSocket } from "node:tls";
import { ProviderNoAnswerError, ProviderNotReachedError } from "./iyzico.gateway.js";

export type IyzicoResponse = Record<string, unknown>;

const HANDSHAKE_ATTEMPTS = 3;
/** Tekrar denemeden önce kısa bekleme (deneme sayısıyla artar). */
const RETRY_BACKOFF_MS = 100;

/**
 * iyzico REST API'sine imzalı POST (IYZWSv2). Resmî Node SDK'sının zaman aşımı yok ve
 * "istek gitti mi, gitmedi mi?" ayrımını yapmıyor; bu yüzden küçük bir istemci yazdık.
 *
 * Ayrım TLS el sıkışmasına göre yapılır: el sıkışma bitmeden istek gövdesi gönderilmez.
 *  - El sıkışmadan önce hata → ProviderNotReachedError (para çekilmedi, tekrar denemek güvenli)
 *  - El sıkışmadan sonra hata / zaman aşımı / okunamayan cevap → ProviderNoAnswerError
 *
 * Bağlantı + el sıkışma normalde ~40 ms sürer. iyzico sandbox, uzun aradan sonraki ilk bağlantıyı
 * el sıkışma sırasında yaklaşık 3,5 sn sonra keser (o süre boyunca yeni bağlantılar da kurulamıyor).
 * El sıkışmaya ayrı bir üst sınır tanırız (handshakeTimeoutMs): dolarsa istek gövdesi hiç gönderilmediği
 * için tekrar denemek güvenlidir.
 */
export class IyzicoHttpClient {
  private readonly log = new Logger(IyzicoHttpClient.name);

  constructor(
    private readonly baseUrl: string,
    private readonly apiKey: string,
    private readonly secretKey: string,
    private readonly timeoutMs: number,
    private readonly handshakeTimeoutMs: number = 10_000,
  ) {}

  async post(path: string, body: object): Promise<IyzicoResponse> {
    for (let attempt = 1; ; attempt++) {
      try {
        return await this.send(path, body);
      } catch (error) {
        // iyzico sandbox, yeni açılan süreçlerin ilk TLS bağlantılarını zaman zaman keser.
        if (error instanceof ProviderNotReachedError && attempt < HANDSHAKE_ATTEMPTS) {
          this.log.warn(`iyzico'ya bağlanılamadı (${attempt}. deneme), tekrar deneniyor: ${error.message}`);
          await sleep(RETRY_BACKOFF_MS * attempt);
          continue;
        }
        throw error;
      }
    }
  }

  private send(path: string, body: object): Promise<IyzicoResponse> {
    const json = JSON.stringify(body);
    const randomKey = `${Date.now()}${randomBytes(6).toString("hex")}`;
    const signature = createHmac("sha256", this.secretKey).update(randomKey + path + json).digest("hex");
    const authorization = Buffer.from(
      `apiKey:${this.apiKey}&randomKey:${randomKey}&signature:${signature}`,
    ).toString("base64");

    return new Promise((resolve, reject) => {
      let handshakeDone = false;
      const fail = (cause: Error) =>
        reject(
          handshakeDone
            ? new ProviderNoAnswerError(`iyzico'dan cevap alınamadı: ${cause.message}`, { cause })
            : new ProviderNotReachedError(`iyzico'ya bağlanılamadı: ${cause.message}`, { cause }),
        );

      const req = request(
        new URL(path, this.baseUrl),
        {
          method: "POST",
          agent: false, // her istek yeni bağlantı: el sıkışma anını kesin bilelim
          maxVersion: "TLSv1.2", // sandbox, TLS 1.3 el sıkışmalarını zaman zaman kesiyor
          timeout: this.timeoutMs,
          headers: {
            "Content-Type": "application/json",
            Accept: "application/json",
            Authorization: `IYZWSv2 ${authorization}`,
            "x-iyzi-rnd": randomKey,
            "Content-Length": Buffer.byteLength(json),
          },
        },
        (res) => {
          const chunks: Buffer[] = [];
          res.on("data", (chunk: Buffer) => chunks.push(chunk));
          res.on("error", fail);
          res.on("end", () => {
            const text = Buffer.concat(chunks).toString("utf8");
            try {
              resolve(JSON.parse(text) as IyzicoResponse);
            } catch {
              fail(new Error(`JSON olmayan cevap (HTTP ${res.statusCode})`));
            }
          });
        },
      );
      req.on("socket", (socket) => {
        // El sıkışma süresi dolarsa bağlantıyı keseriz; gövde henüz gitmediği için "ulaşılamadı" sayılır.
        const handshakeTimer = setTimeout(
          () => req.destroy(new Error(`TLS el sıkışması ${this.handshakeTimeoutMs} ms içinde bitmedi`)),
          this.handshakeTimeoutMs,
        );
        (socket as TLSSocket).once("secureConnect", () => {
          handshakeDone = true;
          clearTimeout(handshakeTimer);
        });
        socket.once("close", () => clearTimeout(handshakeTimer));
      });
      req.on("timeout", () => req.destroy(new Error(`${this.timeoutMs} ms içinde cevap gelmedi`)));
      req.on("error", fail);
      req.end(json);
    });
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
