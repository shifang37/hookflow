# Two-stage build: the runtime image carries a JRE and the jar, nothing else.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependencies first so that source-only changes do not invalidate the cache.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline || true

COPY src/ src/
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as a non-root user: nothing in the service needs write access to the image.
RUN groupadd --system hookflow && useradd --system --gid hookflow hookflow

ENV TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

COPY --from=build /workspace/target/hookflow-*.jar /app/hookflow.jar
USER hookflow

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
  CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1"]

ENTRYPOINT ["java", "-jar", "/app/hookflow.jar"]
