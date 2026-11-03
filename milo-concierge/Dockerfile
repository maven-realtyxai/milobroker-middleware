FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY milo-concierge/pom.xml milo-concierge/pom.xml
COPY milo-marketplace/pom.xml milo-marketplace/pom.xml
COPY milo-atlas/pom.xml milo-atlas/pom.xml
RUN mvn -q -pl milo-concierge -am dependency:go-offline -DskipTests
COPY milo-concierge/src milo-concierge/src
RUN mvn -q -pl milo-concierge -am package -DskipTests

FROM eclipse-temurin:25-jre
RUN useradd -r -u 10001 appuser && mkdir -p /app/logs && chown appuser:appuser /app/logs
USER appuser
WORKDIR /app
COPY --from=build /workspace/milo-concierge/target/milo-concierge-*.jar app.jar
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD bash -c 'cat < /dev/null > /dev/tcp/localhost/8081' || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
