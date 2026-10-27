FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY sample-was/build.gradle.kts sample-was/build.gradle.kts
RUN chmod +x gradlew && ./gradlew --no-daemon --console=plain dependencies
COPY src src
COPY sample-was/src sample-was/src
# The required verification job runs full tests with a Docker daemon.
RUN ./gradlew --no-daemon --console=plain spotlessCheck bootJar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app app
COPY --from=build --chown=app:app /workspace/build/libs/logscope-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
