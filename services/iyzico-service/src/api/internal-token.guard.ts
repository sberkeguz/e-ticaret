import { type CanActivate, type ExecutionContext, Inject, Injectable, SetMetadata } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { timingSafeEqual } from "node:crypto";
import { APP_CONFIG, type AppConfig } from "../config.js";
import { ApiProblem } from "./api-problem.js";

export const INTERNAL_TOKEN_HEADER = "x-internal-token";
const PUBLIC = "public-route";

/** Bu adres token istemez (sağlık kontrolü gibi). */
export const Public = () => SetMetadata(PUBLIC, true);

/**
 * Bu servise yalnızca backend erişir. Backend her istekte X-Internal-Token gönderir.
 * Token tanımlı değilse hiçbir istek geçmez.
 */
@Injectable()
export class InternalTokenGuard implements CanActivate {
  private readonly token: Buffer;

  constructor(
    @Inject(APP_CONFIG) config: AppConfig,
    private readonly reflector: Reflector,
  ) {
    this.token = Buffer.from(config.internalApiToken, "utf8");
  }

  canActivate(context: ExecutionContext): boolean {
    if (this.reflector.getAllAndOverride<boolean>(PUBLIC, [context.getHandler(), context.getClass()])) {
      return true;
    }
    const given = context.switchToHttp().getRequest<{ headers: Record<string, string | undefined> }>()
      .headers[INTERNAL_TOKEN_HEADER];
    const givenBytes = Buffer.from(given ?? "", "utf8");
    const allowed = this.token.length > 0 && givenBytes.length === this.token.length && timingSafeEqual(givenBytes, this.token);
    if (!allowed) {
      throw ApiProblem.unauthorized();
    }
    return true;
  }
}
