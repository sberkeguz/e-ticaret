import { PostgreSqlContainer, type StartedPostgreSqlContainer } from "@testcontainers/postgresql";
import { RabbitMQContainer, type StartedRabbitMQContainer } from "@testcontainers/rabbitmq";
import { execSync } from "node:child_process";
import type { TestProject } from "vitest/node";

/** Testler gerçek Postgres ve gerçek RabbitMQ'da çalışır: kısıtlar, kilitler ve onaylar gerçekten devrede. */
let postgres: StartedPostgreSqlContainer;
let rabbitmq: StartedRabbitMQContainer;

export async function setup(project: TestProject): Promise<void> {
  [postgres, rabbitmq] = await Promise.all([
    new PostgreSqlContainer("postgres:17-alpine").start(),
    new RabbitMQContainer("rabbitmq:4-alpine").start(),
  ]);
  const databaseUrl = `${postgres.getConnectionUri()}?schema=iyzico`;
  execSync("npx prisma migrate deploy", { env: { ...process.env, DATABASE_URL: databaseUrl }, stdio: "inherit" });
  project.provide("databaseUrl", databaseUrl);
  project.provide("rabbitmqUrl", rabbitmq.getAmqpUrl());
}

export async function teardown(): Promise<void> {
  await Promise.all([postgres?.stop(), rabbitmq?.stop()]);
}

declare module "vitest" {
  export interface ProvidedContext {
    databaseUrl: string;
    rabbitmqUrl: string;
  }
}
