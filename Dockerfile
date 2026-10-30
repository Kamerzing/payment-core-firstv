FROM node:24-alpine AS web-build
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-fund --no-audit
COPY web ./
RUN npm run build

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
COPY --from=web-build /web/dist ./web/dist
RUN sh ./gradlew --no-daemon --max-workers=2 -PskipWebBuild bootJar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 payment && adduser -S -u 10001 -G payment payment
WORKDIR /app
COPY --from=build --chown=payment:payment /workspace/build/libs/payment-core.jar app.jar
USER payment
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
