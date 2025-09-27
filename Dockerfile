# Stage 1 - build
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src src

# Make gradlew executable
RUN chmod +x gradlew

# Build
RUN ./gradlew bootJar -x test --no-daemon

# Stage 2 - runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy jar file
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

# Healthcheck
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","app.jar"]