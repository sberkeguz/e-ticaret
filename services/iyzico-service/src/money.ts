import { Prisma } from "./generated/prisma/client.js";

/**
 * Para hiçbir zaman float (JavaScript number) ile hesaplanmaz: 0.1 + 0.2 = 0.30000000000000004.
 * Tutarlar API'de metin ("12.50") olarak taşınır, hesaplar ondalık sayı kütüphanesiyle yapılır.
 */
export type Money = Prisma.Decimal;

/** Sıfırdan büyük, en fazla 2 ondalık basamaklı tutar: "5", "5.0", "12.50" */
export const POSITIVE_MONEY = /^(?!0+(\.0{1,2})?$)\d{1,10}(\.\d{1,2})?$/;

export function money(value: string | number): Money {
  return new Prisma.Decimal(String(value));
}

/** "5" → "5.00" */
export function moneyText(value: string | number | Money): string {
  return (value instanceof Prisma.Decimal ? value : money(value)).toFixed(2);
}

export function sumMoney(values: (string | number)[]): Money {
  return values.reduce<Money>((total, value) => total.plus(money(value)), new Prisma.Decimal(0));
}

/**
 * Gelen JSON'da tutar metin olmalı. Eski istemciler sayı gönderebilir; onları da metne çeviririz
 * (sayının kendisi zaten JSON'dan okunurken oluşmuş olur, burada ek bir hesap yapılmaz).
 */
export function toMoneyText({ value }: { value: unknown }): unknown {
  return typeof value === "number" ? String(value) : value;
}
