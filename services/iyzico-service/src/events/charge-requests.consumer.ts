import { Inject, Injectable, Logger, type OnApplicationBootstrap, type OnModuleDestroy } from "@nestjs/common";
import { type Channel, type ChannelModel, connect, type ConsumeMessage } from "amqplib";
import { plainToInstance } from "class-transformer";
import { validate } from "class-validator";
import { ApiProblem } from "../api/api-problem.js";
import { CreatePaymentRequest } from "../api/payment.dto.js";
import { traceIdFrom } from "../api/trace-ids.js";
import { APP_CONFIG, type AppConfig } from "../config.js";
import { PaymentAttemptService } from "../payment/payment-attempt.service.js";
import { PAYMENTS_EXCHANGE, UNROUTED_EXCHANGE } from "./payment-events.js";

/** Backend'in 2D ödeme komutu (anahtar ve kuyruk adı backend'deki PaymentEventTopology ile aynı). */
export const CHARGE_ROUTING_KEY = "commands.payment.charge";
export const CHARGE_QUEUE = "iyzico.charge-requests";
const RECONNECT_MS = 5_000;
const RETRY_DELAY_MS = 2_000;

export interface ChargeRequested {
  type: "ChargeRequested";
  version: number;
  attemptId: string;
  idempotencyKey: string;
  /** Bu andan sonra iyzico'ya gönderilmez (süresi dolmuş siparişten para çekilmesin). */
  deadline: string;
  request: unknown;
}

/**
 * 2D ödeme kuyrukla gelir: backend isteği kendi outbox'ıyla RabbitMQ'ya yazar, bu tüketici alır,
 * iyzico'ya gönderir; sonuç (PaymentSucceeded/Failed/Verifying) servisin outbox'ıyla olay olarak döner.
 *
 * Güvenlik:
 *  - Aynı istek iki kez gelirse (en az bir kez teslim) Idempotency-Key sayesinde ikinci kez çekim yapılmaz.
 *  - Süresi geçmiş istek iyzico'ya gönderilmez, başarısız olarak yayınlanır.
 *  - Mesaj, işlem bitip sonuç veritabanına yazıldıktan sonra onaylanır (ack); arada servis düşerse mesaj
 *    kuyruğa döner ve tekrar işlenir (yine çift çekim olmaz).
 */
@Injectable()
export class ChargeRequestsConsumer implements OnApplicationBootstrap, OnModuleDestroy {
  private readonly log = new Logger(ChargeRequestsConsumer.name);
  private connection: ChannelModel | undefined;
  private channel: Channel | undefined;
  private reconnect: NodeJS.Timeout | undefined;
  private stopped = false;

  constructor(
    private readonly payments: PaymentAttemptService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  onApplicationBootstrap(): void {
    if (this.config.rabbitmqUrl && this.config.chargeConsumer) {
      void this.start();
    }
  }

  async onModuleDestroy(): Promise<void> {
    this.stopped = true;
    clearTimeout(this.reconnect);
    await this.connection?.close().catch(() => undefined);
  }

  private async start(): Promise<void> {
    try {
      const connection = await connect(this.config.rabbitmqUrl);
      this.connection = connection;
      connection.on("error", () => undefined);
      connection.on("close", () => this.scheduleReconnect("bağlantı kapandı"));
      const channel = await connection.createChannel();
      this.channel = channel;
      await channel.assertExchange(UNROUTED_EXCHANGE, "fanout", { durable: true });
      await channel.assertExchange(PAYMENTS_EXCHANGE, "topic", { durable: true, alternateExchange: UNROUTED_EXCHANGE });
      await channel.assertQueue(CHARGE_QUEUE, { durable: true });
      await channel.bindQueue(CHARGE_QUEUE, PAYMENTS_EXCHANGE, CHARGE_ROUTING_KEY);
      await channel.prefetch(4);
      await channel.consume(CHARGE_QUEUE, (message) => {
        if (message) {
          void this.handle(channel, message);
        }
      });
      this.log.log("2D ödeme istekleri dinleniyor (RabbitMQ)");
    } catch (error) {
      this.scheduleReconnect((error as Error).message);
    }
  }

  private scheduleReconnect(reason: string): void {
    if (this.stopped || this.reconnect) {
      return;
    }
    this.log.warn(`Ödeme isteği kuyruğu bağlantısı yok (${reason}); ${RECONNECT_MS / 1000} sn sonra yeniden denenecek`);
    this.reconnect = setTimeout(() => {
      this.reconnect = undefined;
      void this.start();
    }, RECONNECT_MS);
  }

  private async handle(channel: Channel, message: ConsumeMessage): Promise<void> {
    try {
      await this.process(JSON.parse(message.content.toString()) as ChargeRequested, message);
      channel.ack(message);
    } catch (error) {
      // Beklenmedik hata (ör. veritabanı geçici kapalı): mesaj biraz sonra kuyruğa geri döner.
      this.log.warn(`Ödeme isteği işlenemedi, tekrar denenecek: ${(error as Error).message}`);
      setTimeout(() => {
        try {
          channel.nack(message, false, true);
        } catch {
          /* kanal kapandıysa mesaj zaten kuyruğa döner */
        }
      }, RETRY_DELAY_MS);
    }
  }

  /** Testler doğrudan çağırabilir. */
  async process(command: ChargeRequested, message?: ConsumeMessage): Promise<void> {
    const traceId = traceIdFrom(message?.properties.headers?.traceparent as string | undefined);
    const request = plainToInstance(CreatePaymentRequest, command.request ?? {});
    const orderId = Number(request.orderId);
    const amount = typeof request.amount === "string" && /^\d+(\.\d{1,2})?$/.test(request.amount) ? request.amount : "0.00";
    if (!Number.isSafeInteger(orderId) || orderId <= 0 || !command.attemptId || !command.idempotencyKey) {
      // Hangi siparişe ait olduğu bilinmeyen mesaj: sonucu kimseye bildirilemez, tekrar denemek de işe yaramaz.
      this.log.error(`Okunamayan ödeme isteği atlandı (sipariş: ${String(request.orderId)})`);
      return;
    }

    if (Date.now() > Date.parse(command.deadline)) {
      await this.payments.rejectRequest(command.attemptId, command.idempotencyKey, orderId, amount, traceId,
        "Ödeme isteği zamanında işlenemedi, lütfen tekrar deneyin.");
      return;
    }
    const errors = await validate(request);
    if (errors.length > 0 || request.threeDS) {
      const detail = errors.flatMap((error) => Object.values(error.constraints ?? {})).join(", ") || "3D isteği kuyrukla işlenmez";
      await this.payments.rejectRequest(command.attemptId, command.idempotencyKey, orderId, amount, traceId,
        `Geçersiz ödeme isteği: ${detail}`);
      return;
    }
    try {
      await this.payments.start(command.idempotencyKey, traceId, request, { attemptId: command.attemptId });
    } catch (error) {
      // İş kuralı hatası (ör. sipariş için başka aktif deneme, istek içeriği uyuşmuyor): tekrar denemek
      // sonucu değiştirmez; başarısız olarak bildirilir.
      // "Aynı anahtarla deneme hâlâ işleniyor" da buraya düşer: rejectRequest mevcut denemeyi görüp
      // dokunmadan döner, mesaj onaylanır; o denemenin sonucu kendi akışıyla (veya mutabakatla) yayınlanır.
      const status = error instanceof ApiProblem ? error.status : (error as { getStatus?: () => number }).getStatus?.();
      if (status !== undefined && status >= 400 && status < 500) {
        await this.payments.rejectRequest(command.attemptId, command.idempotencyKey, orderId, amount, traceId,
          (error as Error).message);
        return;
      }
      throw error;
    }
  }
}
