# Repository Guidelines

## Project Structure & Module Organization

This repository contains a Java 21 / Spring Boot 4.1.2 backend and a React / TypeScript / Vite web panel. Gradle uses Kotlin DSL; backend application code is Java.

- `src/main/java/com/example/payments2/payment_core2/`: application entry point and future feature packages. Keep new components beneath this package for Spring scanning; organize by users, wallets, ledger, operations, holds, and audit.
- `src/main/resources/application.properties`: application configuration. Place Flyway migrations in `db/migration/` beneath resources, named `V1__description.sql`.
- `src/test/java/`: tests mirroring production packages.
- `web/src/`: React UI, API client and exact money formatting; `web/e2e/`: Playwright browser scenarios. Gradle builds `web/dist` into JAR static resources. No Android application exists yet.
- `build.gradle.kts`, `settings.gradle.kts`, and `gradle/wrapper/`: build configuration. Do not commit generated `build/`, `.gradle/`, or IDE state.

## Build, Test, and Development Commands

Run from the repository root using JDK 21. Set IntelliJ Project SDK and Gradle JVM to 21; use the checked-in wrapper.
The default Gradle build also requires Node.js 22.12+ and npm. In `web/`, use `npm ci`, `npm run dev`, `npm run build`, `npm test`, and `npm run test:e2e`. Browser tests start an isolated backend and PostgreSQL container on port 18080.

- `.\gradlew.bat classes`: compile application sources and process resources.
- `.\gradlew.bat test`: run the JUnit test suite.
- `.\gradlew.bat test --tests '*PaymentCore2ApplicationTests'`: run the existing context test.
- `.\gradlew.bat clean build`: rebuild, test, and package the application.
- `.\gradlew.bat bootRun`: start the backend locally.

On Linux/macOS, use `./gradlew`. Local startup uses the local profile and `.env`; server startup uses `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`. Tests require Docker and use isolated PostgreSQL Testcontainers, never the local `.env`.

## Coding Style & Naming Conventions

Match existing tab indentation in Java and Gradle files. Use `UpperCamelCase` classes, `lowerCamelCase` methods and fields, and `UPPER_SNAKE_CASE` constants. Prefer constructor injection and separate controllers, services, and JDBC repositories. No formatter or linter is configured; avoid unrelated reformatting.

## Testing Guidelines

Tests use JUnit Jupiter, Spring Boot Test and PostgreSQL 18 Testcontainers. Name classes `*Tests` and methods by expected behavior. No coverage threshold is configured. For financial changes, test rounding, overflow, insufficient funds, retries, transaction rollback, concurrent spending, hold completion races and access control. UI monetary commands must retain the same idempotency key after ambiguous failures, including page reloads; keep passwords and session credentials out of browser storage.

## Commit & Pull Request Guidelines

No Git history is available; no existing commit convention can be inferred. Use concise imperative subjects, optionally prefixed `feat:`, `fix:`, or `docs:`. PRs should describe behavior, link relevant issues, report checks and limitations, and explain migrations. Include screenshots for UI changes.

## Security & Financial Invariants

Never commit credentials. Use integer kopecks, checked arithmetic, and string amounts in JSON. Keep postings immutable and balanced; update balances transactionally. Require idempotency for monetary commands. This remains an educational system using simulated funds.
