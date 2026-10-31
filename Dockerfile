# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests package \
 && java -Djarmode=layertools -jar target/multitenant-notification-service-*.jar extract --destination /layers

FROM eclipse-temurin:24-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=app:app /layers/dependencies/ ./
COPY --from=build --chown=app:app /layers/spring-boot-loader/ ./
COPY --from=build --chown=app:app /layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /layers/application/ ./
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness | grep -q UP || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
