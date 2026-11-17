FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080

# Uygulama root olarak değil, yetkisiz bir kullanıcıyla çalışır: bir açıkta konteyner içinde
# her şeyi yapamaz.
RUN addgroup -S app && adduser -S -G app app
USER app

# iyzico ile TLS 1.2: sandbox, TLS 1.3 el sıkışmalarını zaman zaman kesiyor.
ENTRYPOINT ["java", "-Djdk.tls.client.protocols=TLSv1.2", "-jar", "app.jar"]