FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY workflow-agent-service/ ./
RUN mvn -pl workflow-boot -am -DskipTests package

FROM eclipse-temurin:25-jre

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home workflow
WORKDIR /app
COPY --from=build /workspace/workflow-boot/target/workflow-boot-*.jar app.jar
USER workflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
