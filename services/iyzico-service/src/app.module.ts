import { Module } from "@nestjs/common";
import { APP_FILTER, APP_GUARD } from "@nestjs/core";
import { HealthController } from "./api/health.controller.js";
import { InternalTokenGuard } from "./api/internal-token.guard.js";
import { PaymentController } from "./api/payment.controller.js";
import { ProblemFilter } from "./api/problem.filter.js";
import { AttemptStore } from "./attempt/attempt.store.js";
import { APP_CONFIG, loadConfig } from "./config.js";
import { OutboxRelay } from "./events/outbox-relay.js";
import { BackgroundWork } from "./payment/background-work.js";
import { Reconciler } from "./payment/reconciler.js";
import { PaymentAttemptService } from "./payment/payment-attempt.service.js";
import { PrismaService } from "./prisma.service.js";
import { IyzicoApiGateway } from "./provider/iyzico-api.gateway.js";
import { IyzicoGateway } from "./provider/iyzico.gateway.js";

@Module({
  controllers: [PaymentController, HealthController],
  providers: [
    { provide: APP_CONFIG, useFactory: () => loadConfig() },
    PrismaService,
    AttemptStore,
    PaymentAttemptService,
    BackgroundWork,
    Reconciler,
    OutboxRelay,
    { provide: IyzicoGateway, useClass: IyzicoApiGateway },
    { provide: APP_GUARD, useClass: InternalTokenGuard },
    { provide: APP_FILTER, useClass: ProblemFilter },
  ],
})
export class AppModule {}
