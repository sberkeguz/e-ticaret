import "reflect-metadata";
import { NestFactory } from "@nestjs/core";
import { AppModule } from "./app.module.js";
import { configureApp } from "./app.setup.js";
import { loadConfig } from "./config.js";

const app = await NestFactory.create(AppModule);
configureApp(app);
await app.listen(loadConfig().port);
