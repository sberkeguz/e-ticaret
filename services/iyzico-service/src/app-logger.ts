import { ConsoleLogger, type LogLevel } from "@nestjs/common";
import { logs, SeverityNumber } from "@opentelemetry/api";

const SEVERITY: Record<LogLevel, SeverityNumber> = {
  verbose: SeverityNumber.TRACE,
  debug: SeverityNumber.DEBUG,
  log: SeverityNumber.INFO,
  warn: SeverityNumber.WARN,
  error: SeverityNumber.ERROR,
  fatal: SeverityNumber.FATAL,
};

/**
 * Nest'in konsol logu + OpenTelemetry. LOG_FORMAT=json ise konsola JSON yazar (Docker'da).
 * Her satır OpenTelemetry'ye de gider (Grafana'da Loki) ve o anki izle (trace) eşleşir.
 * OpenTelemetry açık değilse (OTEL_EXPORTER_OTLP_ENDPOINT yok) gönderim hiçbir şey yapmaz.
 */
export class AppLogger extends ConsoleLogger {
  private readonly otel = logs.getLogger("iyzico-service");

  constructor() {
    super({ json: process.env.LOG_FORMAT === "json" });
  }

  protected override printMessages(
    messages: unknown[],
    context = "",
    logLevel: LogLevel = "log",
    writeStreamType?: "stdout" | "stderr",
    errorStack?: unknown,
    params?: Record<string, unknown>,
  ): void {
    super.printMessages(messages, context, logLevel, writeStreamType, errorStack, params);
    for (const message of messages) {
      this.otel.emit({
        severityNumber: SEVERITY[logLevel],
        severityText: logLevel.toUpperCase(),
        body: typeof message === "string" ? message : JSON.stringify(message),
        attributes: {
          "nest.context": context,
          ...(errorStack ? { "exception.stacktrace": String(errorStack) } : {}),
        },
      });
    }
  }
}
