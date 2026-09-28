# ---- build stage: full JDK + Maven wrapper -------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve dependencies in their own layer: it is only rebuilt when pom.xml changes, not on every code edit.
COPY mvnw pom.xml ./
COPY .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/
RUN ./mvnw -B -q dependency:go-offline

COPY src/main src/main
# Tests need Docker (Testcontainers), which isn't available inside a build; CI runs `mvn verify` instead.
RUN ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/url-shortener-*.jar extract --layers --launcher --destination extracted

# ---- runtime stage: JRE only, non-root ------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app

# Spring Boot layers, least to most frequently changed, so a code change only replaces the last layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8000
# Size the heap from the container's memory limit rather than the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
