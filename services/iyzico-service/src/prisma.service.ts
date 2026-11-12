import { Inject, Injectable, type OnApplicationShutdown } from "@nestjs/common";
import { PrismaPg } from "@prisma/adapter-pg";
import { PrismaClient } from "./generated/prisma/client.js";
import { APP_CONFIG, type AppConfig } from "./config.js";

/** Servisin kendi şeması. Ham SQL sorgularında da bu ad kullanılır. */
export const DB_SCHEMA = "iyzico";

@Injectable()
export class PrismaService extends PrismaClient implements OnApplicationShutdown {
  constructor(@Inject(APP_CONFIG) config: AppConfig) {
    super({ adapter: new PrismaPg({ connectionString: config.databaseUrl }, { schema: DB_SCHEMA }) });
  }

  // En son kapanır: arka planda süren ödemeler (onModuleDestroy'da beklenir) veritabanına yazabilsin.
  async onApplicationShutdown(): Promise<void> {
    await this.$disconnect();
  }
}
