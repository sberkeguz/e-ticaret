import { type ArgumentsHost, Catch, type ExceptionFilter, HttpException, HttpStatus, Logger } from "@nestjs/common";
import type { Response } from "express";
import { AttemptNotFoundError } from "../attempt/attempt.store.js";
import { ApiProblem } from "./api-problem.js";

/** Bütün hatalar RFC 9457 problem olarak döner (application/problem+json). */
@Catch()
export class ProblemFilter implements ExceptionFilter {
  private readonly log = new Logger(ProblemFilter.name);

  catch(error: unknown, host: ArgumentsHost): void {
    const http = host.switchToHttp();
    const response = http.getResponse<Response>();
    const instance = http.getRequest<{ url: string }>().url;

    const problem = this.toProblem(error);
    response
      .status(problem.status)
      .type("application/problem+json")
      .json({ type: `/problems/${problem.type}`, title: problem.title, status: problem.status, detail: problem.message, instance });
  }

  private toProblem(error: unknown): ApiProblem {
    if (error instanceof ApiProblem) {
      return error;
    }
    if (error instanceof AttemptNotFoundError) {
      return ApiProblem.attemptNotFound(error.message);
    }
    if (error instanceof HttpException && error.getStatus() < 500) {
      // Nest'in kendi hataları (ör. bulunamayan adres, bozuk JSON)
      const status = error.getStatus();
      const body = error.getResponse();
      const detail = typeof body === "object" && body && "message" in body ? String(body.message) : error.message;
      const type = status === HttpStatus.NOT_FOUND ? "not-found" : "bad-request";
      return ApiProblem.of(status, type, String(HttpStatus[status] ?? "Hata"), detail);
    }
    this.log.error("Beklenmeyen hata", error instanceof Error ? error.stack : String(error));
    return ApiProblem.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Beklenmeyen hata", "Beklenmeyen bir hata oluştu.");
  }
}
