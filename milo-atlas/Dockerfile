# Build: docker build -f milo-atlas/Dockerfile -t milo-atlas:temp .
# Run (on the EC2 host, same docker network as postgres):
#   docker run -d --name milo-atlas --restart unless-stopped -p 8083:8083 \
#     -e ATLAS_DB_URL=jdbc:postgresql://postgres:5432/staticdb?sslmode=disable \
#     -e ATLAS_DB_USERNAME=staticdb_app -e ATLAS_DB_PASSWORD='<from SSM>' \
#     milo-atlas:temp
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY milo-atlas/pom.xml milo-atlas/pom.xml
COPY milo-marketplace/pom.xml milo-marketplace/pom.xml
COPY milo-concierge/pom.xml milo-concierge/pom.xml
RUN mvn -q -pl milo-atlas -am dependency:go-offline -DskipTests
COPY milo-atlas/src milo-atlas/src
RUN mvn -q -pl milo-atlas -am package -DskipTests
FROM eclipse-temurin:25-jre AS runtime
RUN useradd -r -u 10001 appuser && mkdir -p /app/logs && chown appuser:appuser /app/logs
USER appuser
WORKDIR /app
COPY --from=build /workspace/milo-atlas/target/milo-atlas-*.jar app.jar
EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD bash -c 'cat < /dev/null > /dev/tcp/localhost/8083' || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
