import { type INestApplication, ValidationPipe } from "@nestjs/common";
import { DocumentBuilder, SwaggerModule } from "@nestjs/swagger";
import { ApiProblem } from "./api/api-problem.js";
import { INTERNAL_TOKEN_HEADER } from "./api/internal-token.guard.js";

/** Uygulamanın ortak ayarları; testler de aynısını kullanır. */
export function configureApp(app: INestApplication): void {
  app.useGlobalPipes(
    new ValidationPipe({
      whitelist: true,
      transform: true,
      exceptionFactory: (errors) => {
        const first = errors[0];
        const message = first ? describe(first) : "İstek doğrulanamadı.";
        return ApiProblem.validation(message);
      },
    }),
  );
  app.enableShutdownHooks();

  const document = SwaggerModule.createDocument(
    app,
    new DocumentBuilder()
      .setTitle("IyzicoService")
      .setDescription("iyzico ile konuşan tek servis. Yalnızca backend çağırır (X-Internal-Token).")
      .setVersion("1")
      .addApiKey({ type: "apiKey", in: "header", name: INTERNAL_TOKEN_HEADER }, "internal-token")
      .build(),
  );
  SwaggerModule.setup("swagger-ui", app, document, { jsonDocumentUrl: "v3/api-docs" });
}

function describe(error: { property: string; constraints?: Record<string, string>; children?: unknown[] }): string {
  if (error.constraints) {
    return Object.values(error.constraints)[0] ?? error.property;
  }
  const child = (error.children as (typeof error)[] | undefined)?.[0];
  return child ? `${error.property}.${describe(child)}` : `${error.property} geçersiz`;
}
