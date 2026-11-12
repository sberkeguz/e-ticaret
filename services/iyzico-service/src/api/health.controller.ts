import { Controller, Get, ServiceUnavailableException } from "@nestjs/common";
import { ApiExcludeController } from "@nestjs/swagger";
import { PrismaService } from "../prisma.service.js";
import { Public } from "./internal-token.guard.js";

/** Docker sağlık kontrolü: servis ayakta ve veritabanına ulaşıyor mu? */
@ApiExcludeController()
@Public()
@Controller("health")
export class HealthController {
  constructor(private readonly db: PrismaService) {}

  @Get()
  async health(): Promise<{ status: string }> {
    try {
      await this.db.$queryRaw`SELECT 1`;
      return { status: "UP" };
    } catch {
      throw new ServiceUnavailableException("Veritabanına ulaşılamıyor.");
    }
  }
}
