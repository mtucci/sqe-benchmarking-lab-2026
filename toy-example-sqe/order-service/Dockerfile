# Multi-stage build of order-service. Build context: the repository root (see compose.yaml).

# ---- Stage 1: compile and package the fat jar with Maven (Java 17) ----
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml pom.xml
# Maven reads every module's pom.xml of the reactor, but only these two modules are compiled
COPY service-commons service-commons
COPY order-service order-service
COPY gateway-service/pom.xml gateway-service/pom.xml
COPY workflow-service/pom.xml workflow-service/pom.xml
COPY risk-processing-service/pom.xml risk-processing-service/pom.xml
COPY pricing-profile-service/pom.xml pricing-profile-service/pom.xml
COPY benchmark-tools/pom.xml benchmark-tools/pom.xml
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -pl order-service -am package -Dmaven.test.skip=true

# ---- Stage 2: small Java 17 runtime image ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/order-service/target/app.jar app.jar
# Conservative JVM defaults for student laptops (override with JAVA_OPTS in compose.yaml)
ENV JAVA_OPTS="-Xms64m -Xmx192m -XX:MaxMetaspaceSize=96m -Xss512k -XX:+UseSerialGC"
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
    CMD curl -fsS http://localhost:8081/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
