import "reflect-metadata";
import type { INestApplication } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import { randomInt, randomUUID } from "node:crypto";
import { inject } from "vitest";
import { plainToInstance } from "class-transformer";
import { CreatePaymentRequest } from "../src/api/payment.dto.js";
import { AppModule } from "../src/app.module.js";
import { configureApp } from "../src/app.setup.js";
import { APP_CONFIG, type AppConfig, loadConfig } from "../src/config.js";
import { IyzicoGateway } from "../src/provider/iyzico.gateway.js";
import { FakeIyzicoGateway } from "./fake-iyzico.gateway.js";

export const TOKEN = "test-token";

/** Testlerde zamanlanmış işler kapalı (gerekirse test açar); iyzico'ya gerçek istek gitmez. */
function testConfig(overrides: Partial<AppConfig>): AppConfig {
  const base = loadConfig({});
  return {
    ...base,
    databaseUrl: inject("databaseUrl"),
    internalApiToken: TOKEN,
    reconcileIntervalMs: 0,
    cleanupIntervalMs: 0,
    outboxListen: false,
    iyzico: { ...base.iyzico, keepWarmIntervalMs: 0 },
    ...overrides,
  };
}

/** Gerçek Postgres (Testcontainers) + sahte iyzico ile tam uygulama. */
export async function createTestApp(
  overrides: Partial<AppConfig> = {},
): Promise<{ app: INestApplication; iyzico: FakeIyzicoGateway }> {
  const iyzico = new FakeIyzicoGateway();
  const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
    .overrideProvider(APP_CONFIG)
    .useValue(testConfig(overrides))
    .overrideProvider(IyzicoGateway)
    .useValue(iyzico)
    .compile();
  const app = moduleRef.createNestApplication({ logger: ["error"] });
  configureApp(app);
  await app.init();
  return { app, iyzico };
}

// Her test dosyası farklı bir aralıktan başlar: aynı sipariş numarası iki dosyada kullanılırsa
// veritabanı (doğru olarak) ikinci başarılı ödemeyi reddeder.
let lastOrderId = randomInt(1, 1_000_000) * 1_000;

export function newOrderId(): number {
  return ++lastOrderId;
}

export function newKey(): string {
  return randomUUID();
}

export function paymentBody(orderId: number, amount: number, threeDS = false) {
  return {
    orderId,
    amount,
    threeDS,
    clientIp: "127.0.0.1",
    buyer: {
      id: "7", firstName: "Test", lastName: "Alıcı", email: "test@example.com", phone: "05551234567",
      identityNumber: "11111111111", city: "İstanbul", address: "Moda Cad. 1",
    },
    address: { contactName: "Test Alıcı", city: "İstanbul", address: "Moda Cad. 1" },
    items: [{ id: "1", name: "Kalem", price: amount }],
    card: { holderName: "Test Alıcı", number: "5528790000000008", expireMonth: "12", expireYear: "2030", cvc: "123" },
  };
}

/** Servisi doğrudan çağıran testler için doğrulanmış DTO. */
export function paymentRequest(orderId: number, amount: number, threeDS = false): CreatePaymentRequest {
  return plainToInstance(CreatePaymentRequest, paymentBody(orderId, amount, threeDS));
}
