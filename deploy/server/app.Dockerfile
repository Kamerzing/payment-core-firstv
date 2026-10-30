# Build context: build/libs, after the Gradle build and its tests pass.
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine
FROM ${JAVA_RUNTIME_IMAGE}
USER root
RUN if ! id payment >/dev/null 2>&1; then addgroup -S -g 10001 payment && adduser -S -u 10001 -G payment payment; fi \
    && test "$(id -u payment)" = 10001 && test "$(id -g payment)" = 10001
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError"
COPY --chown=payment:payment payment-core.jar app.jar
USER payment
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
