import { defineConfig } from "prisma/config";

// Bağlantı adresi ortam değişkeninden gelir; şema adresin sonunda: ?schema=iyzico
export default defineConfig({
  schema: "prisma/schema.prisma",
  migrations: {
    path: "prisma/migrations",
  },
  datasource: {
    url: process.env["DATABASE_URL"] ?? "postgresql://localhost:5432/eticaret?schema=iyzico",
  },
});
